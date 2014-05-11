package br.ufmg.dcc.labsoft.jextract.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.internal.corext.refactoring.code.InlineMethodRefactoring;
import org.eclipse.jdt.internal.corext.refactoring.code.InlineMethodRefactoring.Mode;
import org.eclipse.ltk.core.refactoring.Change;

import br.ufmg.dcc.labsoft.jextract.ranking.ExtractMethodRecomendation;
import br.ufmg.dcc.labsoft.jextract.ranking.ExtractionSlice;
import br.ufmg.dcc.labsoft.jextract.ranking.ExtractionSlice.Fragment;
import br.ufmg.dcc.labsoft.jextract.ranking.StatementsCountVisitor;
import br.ufmg.dcc.labsoft.jextract.ranking.Utils;

public class ProjectInliner {

	private static final String MARKER_CLOSE = "/*}*/";
	//private static final String MARKER_BODY = ";";
	private static final String MARKER_OPEN = "/*{*/";
	Map<String, MethodData> mMap;
	Set<String> modifiedMethods;
	private int minSize = 3;
	private int methodsAnalysed = 0;
	private int methodsInlined = 0;
	private IProgressMonitor pm = new NullProgressMonitor();

	public ProjectInliner() {
		this.mMap = new HashMap<String, MethodData>();
		this.modifiedMethods = new HashSet<String>();
	}

	public void run(IProject project) throws Exception {
		Iterable<ICompilationUnit> files = this.findCandidateFiles(project);
		for (ICompilationUnit icu : files) {
			CompilationUnit cu = this.compile(icu, true);
			Iterable<String> methodKeys = this.findCandidateMethods(icu);
			for (String mKey : methodKeys) {
				this.registerMethod(cu, mKey);
			}
		}
		
		VisibilityRewriter rewriter = new VisibilityRewriter(this.pm);
		for (ICompilationUnit icu : files) {
			rewriter.rewrite(icu);
		}
		
		for (ICompilationUnit icu : files) {
			Iterable<String> methodKeys = this.findCandidateMethods(icu);
			for (String mKey : methodKeys) {
				this.applyBestInline(icu, mKey);
			}
		}
		System.out.println(String.format("%d methods analysed, %d methods inlined", methodsAnalysed, methodsInlined));
	}

	public List<ExtractMethodRecomendation> extractGoldSet(IProject project) throws CoreException {
		final List<ExtractMethodRecomendation> emrList = new ArrayList<ExtractMethodRecomendation>();
		Iterable<ICompilationUnit> files = this.findCandidateFiles(project);
		for (ICompilationUnit icu : files) {
			CompilationUnit cu = this.compile(icu, true);
			Iterable<String> methodKeys = this.findCandidateMethods(icu);
			for (String mKey : methodKeys) {
				this.extractEmr(emrList, icu, cu, mKey);
			}
		}
		return emrList;
	}
	
	private Iterable<ICompilationUnit> findCandidateFiles(IProject project) throws CoreException {
		final List<ICompilationUnit> files = new ArrayList<ICompilationUnit>();
		project.accept(new IResourceVisitor() {
			@Override
			public boolean visit(IResource resource) throws CoreException {
				if (resource instanceof IFile && resource.getName().endsWith(".java")) {
					files.add(((ICompilationUnit) JavaCore.create((IFile) resource)));
				}
				return true;
			}
		});
		return files;
	}

	private Iterable<String> findCandidateMethods(ICompilationUnit icu) {
		final List<String> methods = new ArrayList<String>();
		CompilationUnit cu = this.compile(icu, true);
		cu.accept(new ASTVisitor() {
			public boolean visit(MethodDeclaration node) {
				if (!node.isConstructor()) {
					methods.add(node.resolveBinding().getKey());
				}
				return false;
			}
		});
		return methods;
	}

	private void applyBestInline(ICompilationUnit icu, String mKey) {
		List<MethodInvocationCandidate> list = findMethodInvocations(icu, mKey);
		Collections.sort(list, new Comparator<MethodInvocationCandidate>() {
			@Override
			public int compare(MethodInvocationCandidate o1, MethodInvocationCandidate o2) {
				if (o1.isSameClass() && !o2.isSameClass()) {
					return 1;
				}
				if (!o1.isSameClass() && o2.isSameClass()) {
					return -1;
				}
				return -(o1.getSize() - o2.getSize());
			}
		});
		
		for (MethodInvocationCandidate mic : list) {
			boolean applied = applyInlineMethod(icu, mic);
			if (applied) {
				this.methodsInlined++;
				String changedMethod = mic.getInvoker();
				modifiedMethods.add(changedMethod);
				break;
			}
		}
	}

	private boolean isValid(String mKey, MethodDeclaration methodDeclaration) {
		if (this.modifiedMethods.contains(mKey)) {
			// N�o alterar m�todos que j� foram alterados; 
			return false;
		}
		StatementsCountVisitor counter = new StatementsCountVisitor();
		methodDeclaration.accept(counter);
		return counter.getCount() > this.minSize;
	}

	private CompilationUnit compile(ICompilationUnit icu, boolean resolveBindings) {
		ASTParser parser = ASTParser.newParser(AST.JLS4);
		parser.setSource(icu);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setResolveBindings(resolveBindings);
		return (CompilationUnit) parser.createAST(null);
	}

	private boolean meetsJdtPreconditions(ICompilationUnit icu, CompilationUnit cu, int start, int length) {
		try {
			InlineMethodRefactoring refactoring = InlineMethodRefactoring.create(icu, cu, start, length);
			refactoring.setDeleteSource(false);
			refactoring.setCurrentMode(Mode.INLINE_SINGLE); // or INLINE SINGLE based on the user's intervention
			return refactoring.checkAllConditions(this.pm).isOK();
		} catch (CoreException e) {
			throw new RuntimeException(e);
		}
	}

	private boolean applyInlineMethod(ICompilationUnit icu, MethodInvocationCandidate mic) {
		try {
			CompilationUnit cu = this.compile(icu, true);
			MethodInvocation invocation = this.findMethodInvocationNode(cu, mic.getInvoker(), mic.getInvoked(), mic.getInvocation());
			int start = invocation.getStartPosition();
			int length = invocation.getLength();

			// Insert marker
			Statement enclosingStatement = findEnclosingStatement(invocation);
			boolean insideBlock = enclosingStatement.getParent() instanceof Block;

			final String backup = icu.getSource();
			
			int markerStart = enclosingStatement.getStartPosition();
			int markerOffset = insertMarker(icu, markerStart, enclosingStatement.getLength(), insideBlock);

			// Inline method
			cu = this.compile(icu, true);
			InlineMethodRefactoring refactoring = InlineMethodRefactoring.create(icu, cu, start + markerOffset, length);
			if (refactoring == null) {
				System.out.println(String.format("NULL inlined %s %s <= %s %d", mic.isSameClass() ? "S" : "D", mic.getInvoker(), mic.getInvoked(), mic.getSize()));
				return false;
			}
			refactoring.setDeleteSource(false);
			refactoring.setCurrentMode(Mode.INLINE_SINGLE); // or INLINE SINGLE based on the user's intervention
			if (!refactoring.checkAllConditions(this.pm).isOK()) {
				throw new RuntimeException("preconditions failed for " + mic.getInvoker());
			}
			Change change = refactoring.createChange(this.pm);
			change.perform(this.pm);

			
			// Check for compilation problems
			final ProblemDetector problemDetector = new ProblemDetector();
			ICompilationUnit workingCopy = icu.getWorkingCopy(new WorkingCopyOwner() {
				@Override
				public IProblemRequestor getProblemRequestor(ICompilationUnit workingCopy) {
					return problemDetector;
				}
			}, this.pm);
			if (problemDetector.hasProblems()) {
				workingCopy.getBuffer().setContents(backup);
				workingCopy.reconcile(ICompilationUnit.NO_AST, false, null, null);
				workingCopy.commitWorkingCopy(false, this.pm);
				workingCopy.discardWorkingCopy();
				System.out.println(String.format("ERROR inlined %s %s <= %s %d", mic.isSameClass() ? "S" : "D", mic.getInvoker(), mic.getInvoked(), mic.getSize()));
				return false;
			} else {
				workingCopy.reconcile(ICompilationUnit.NO_AST, false, null, null);
				workingCopy.commitWorkingCopy(false, this.pm);
				workingCopy.discardWorkingCopy();
				System.out.println(String.format("inlined %s %s <= %s %d", mic.isSameClass() ? "S" : "D", mic.getInvoker(), mic.getInvoked(), mic.getSize()));
				return true;
			}
			//removeMarker(icu, markerStart, insideBlock);

		} catch (CoreException e) {
			throw new RuntimeException(e);
		}
	}

	private int insertMarker(ICompilationUnit icu, int startPosition, int length, boolean insideBlock) throws JavaModelException {
		//IProgressMonitor pm = new NullProgressMonitor();
		ICompilationUnit wc = icu.getWorkingCopy(pm);
		IBuffer buffer = wc.getBuffer();
		String content = buffer.getContents();
		
		buffer.setContents(content.substring(0, startPosition));
//		if (!insideBlock) {
//			buffer.append("{");
//		}
//		String marker = MARKER_OPEN + MARKER_BODY + MARKER_CLOSE;
//		buffer.append(marker);
		buffer.append(MARKER_OPEN);
		buffer.append(content.substring(startPosition, startPosition + length));
		buffer.append(MARKER_CLOSE);
//		if (!insideBlock) {
//			buffer.append("}");
//		}
		buffer.append(content.substring(startPosition + length));
		wc.reconcile(ICompilationUnit.NO_AST, false, null, pm);
		
		wc.commitWorkingCopy(false, pm);
		wc.discardWorkingCopy();
		
//		if (!insideBlock) {
//			return marker.length() + 1;
//		}
		return MARKER_OPEN.length();
	}

	private void removeMarker(ICompilationUnit icu, int markerStart, boolean insideBlock) throws JavaModelException {
//		IProgressMonitor pm = new NullProgressMonitor();
//		ICompilationUnit wc = icu.getWorkingCopy(pm);
//		IBuffer buffer = ((IOpenable) wc).getBuffer();
//		String content = buffer.getContents();
//		
//		int start = insideBlock ? markerStart : markerStart + 1;
//		buffer.setContents(content.substring(0, start + MARKER_OPEN.length()));
//		buffer.append(content.substring(start + MARKER_OPEN.length() + MARKER_BODY.length()));
//		wc.reconcile(ICompilationUnit.NO_AST, false, null, pm);
//		
//		wc.commitWorkingCopy(false, pm);
//		wc.discardWorkingCopy();
	}

	private Statement findEnclosingStatement(ASTNode astNode) {
		Statement parent = Utils.findEnclosingStatement(astNode); 
		if (parent == null) {
			throw new RuntimeException("No parent statement found:\n" + astNode);
		}
		return parent;
	}

	private void registerMethod(CompilationUnit cu, String mKey) {
		MethodDeclaration methodDeclaration = (MethodDeclaration) cu.findDeclaringNode(mKey);
		StatementsCountVisitor counter = new StatementsCountVisitor();
		methodDeclaration.accept(counter);
		int size = counter.getCount();
		
		this.mMap.put(mKey, new MethodData(size));
	}

	private void extractEmr(List<ExtractMethodRecomendation> emrList, ICompilationUnit icu, CompilationUnit cu, String mKey) throws JavaModelException {
		MethodDeclaration methodDeclaration = (MethodDeclaration) cu.findDeclaringNode(mKey);
		IMethodBinding methodBinding = methodDeclaration.resolveBinding();
		final String methodSignature = methodBinding.toString();
		final String declaringType = methodBinding.getDeclaringClass().getQualifiedName();
		
		int start = methodDeclaration.getStartPosition();
		String methodSource = icu.getSource().substring(start, start + methodDeclaration.getLength());
		int sliceStart = methodSource.indexOf(MARKER_OPEN) + MARKER_OPEN.length();
		int sliceEnd = methodSource.indexOf(MARKER_CLOSE);
		if (sliceStart >= 0 && sliceEnd > sliceStart) {
			Fragment fragment = new Fragment(start + sliceStart, start + sliceEnd, false);
			boolean canExtract = Utils.canExtract(icu, fragment.start, fragment.length());
			if (canExtract) {
				emrList.add(new ExtractMethodRecomendation(
					emrList.size() + 1,
					declaringType,
					methodSignature,
					new ExtractionSlice(fragment)
				));
			}
		}
	}

	private List<MethodInvocationCandidate> findMethodInvocations(final ICompilationUnit icu, final String mKey) {
		final List<MethodInvocationCandidate> invocations = new ArrayList<MethodInvocationCandidate>();
		
		CompilationUnit cu = this.compile(icu, true);
		MethodDeclaration methodDeclaration = (MethodDeclaration) cu.findDeclaringNode(mKey);
		if (!this.isValid(mKey, methodDeclaration)) {
			return invocations;
		}
		
		final IMethodBinding invoker = methodDeclaration.resolveBinding();
		final ITypeBinding callerClass = invoker.getDeclaringClass();
		
		StatementsCountVisitor counter = new StatementsCountVisitor();
		methodDeclaration.accept(counter);
		final int size = counter.getCount();
		
		if (size < this.minSize) {
			return invocations;
		}
		this.methodsAnalysed++;

		Map<String, List<MethodInvocation>> map = this.findMethodInvocationNodes(cu, mKey);
		for (Map.Entry<String, List<MethodInvocation>> entry : map.entrySet()) {
			String invokedKey = entry.getKey();
			List<MethodInvocation> list = entry.getValue();
			for (int i = 0; i < list.size(); i++) {
				MethodInvocation node = list.get(i);
				final IMethodBinding invokedMethod = node.resolveMethodBinding();
				
				if (isInvokedValid(invoker, invokedMethod)) {
					final ITypeBinding invokedClass = invokedMethod.getDeclaringClass();
					boolean sameClass = callerClass.equals(invokedClass);
					if (meetsJdtPreconditions(icu, cu, node.getStartPosition(), node.getLength())) {
						MethodInvocationCandidate mic = new MethodInvocationCandidate(icu, invoker.getKey(), i, invokedKey, getInvokedMethodSize(invokedMethod), sameClass);
						invocations.add(mic);
						//System.out.println(String.format("candidate %s %s <= %s %d", mic.isSameClass() ? "S" : "D", mic.getInvoker(), mic.getInvoked(), mic.getSize()));
					}
				}
			}
		}
		
		return invocations;
	}
	
	private MethodInvocation findMethodInvocationNode(CompilationUnit cu, String invokerKey, String invokedKey, int position) {
		return this.findMethodInvocationNodes(cu, invokerKey).get(invokedKey).get(position);
	}

	private Map<String, List<MethodInvocation>> findMethodInvocationNodes(CompilationUnit cu, String mKey) {
		final Map<String, List<MethodInvocation>> invocations = new HashMap<String, List<MethodInvocation>>();
		MethodDeclaration methodDeclaration = (MethodDeclaration) cu.findDeclaringNode(mKey);
		methodDeclaration.accept(new ASTVisitor() {
			public boolean visit(MethodInvocation node) {
				final IMethodBinding invokedMethod = node.resolveMethodBinding();
				String invokedKey = invokedMethod.getKey();
				if (!invocations.containsKey(invokedKey)) {
					invocations.put(invokedKey, new ArrayList<MethodInvocation>());
				}
				invocations.get(invokedKey).add(node);
				return true;
			}
		});
		return invocations;
	}
	
	private boolean isInvokedValid(IMethodBinding caller, IMethodBinding invokedMethod) {
		MethodData invokedData = mMap.get(invokedMethod.getKey());
		MethodData callerData = mMap.get(caller.getKey());
		if (invokedData == null || callerData == null) {
			return false;
		}
		if (invokedData.size < this.minSize || callerData.size < this.minSize) {
			return false;
		}
//		double ratio = ((double) invokedData.size) / callerData.size;
//		if (ratio > 2.0) {
//			return false;
//		}
//		if (ratio < 0.25) {
//			return false;
//		}
		
		if (this.modifiedMethods.contains(invokedMethod.getKey())) {
			return false;
		}
		final ITypeBinding callerClass = caller.getDeclaringClass();
		final ITypeBinding invokedClass = invokedMethod.getDeclaringClass();
		boolean sameClass = callerClass.equals(invokedClass);
		boolean samePackage = callerClass.getPackage().equals(invokedClass.getPackage());
//		if (sameClass) {
//			return false;
//		}
		
		return true;
	}
	
	private int getInvokedMethodSize(IMethodBinding invokedMethod) {
		return mMap.get(invokedMethod.getKey()).size;
	}
}
