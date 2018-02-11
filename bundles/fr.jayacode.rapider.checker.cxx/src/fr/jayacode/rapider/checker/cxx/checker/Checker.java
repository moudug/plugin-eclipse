/**
 * 
 */
package fr.jayacode.rapider.checker.cxx.checker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.cdt.codan.core.CodanRuntime;
import org.eclipse.cdt.codan.core.cxx.externaltool.IInvocationParametersProvider;
import org.eclipse.cdt.codan.core.cxx.externaltool.InvocationFailure;
import org.eclipse.cdt.codan.core.cxx.externaltool.InvocationParameters;
import org.eclipse.cdt.codan.core.cxx.externaltool.InvocationParametersProvider;
import org.eclipse.cdt.codan.core.model.AbstractCheckerWithProblemPreferences;
import org.eclipse.cdt.codan.core.model.CheckerLaunchMode;
import org.eclipse.cdt.codan.core.model.IProblem;
import org.eclipse.cdt.codan.core.model.IProblemLocation;
import org.eclipse.cdt.codan.core.model.IProblemLocationFactory;
import org.eclipse.cdt.codan.core.model.IProblemWorkingCopy;
import org.eclipse.cdt.core.ErrorParserManager;
import org.eclipse.cdt.core.IConsoleParser;
import org.eclipse.cdt.core.IMarkerGenerator;
import org.eclipse.cdt.core.ProblemMarkerInfo;
import org.eclipse.core.filesystem.URIUtil;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import fr.jayacode.rapider.checker.cxx.Activator;
import fr.jayacode.rapider.checker.cxx.Messages;

/**
 * @author cconversin
 *
 */
public class Checker extends AbstractCheckerWithProblemPreferences implements IMarkerGenerator {

	private static final String RAPIDER_TOOL_NAME = Messages.Checker_RapiderToolName;
	private static final Collection<String> PROCESSED_EXTENSIONS = Arrays.asList("cpp", "CPP", "c", "C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$//$NON-NLS-4$

	private static final String RAPIDER_PARSER_ID = "fr.jayacode.rapider.checker.cxx.parser"; //$NON-NLS-1$
	private static final String RAPIDER_PROBLEM_ID = "fr.jayacode.rapider.checker.cxx.rapiderProblem"; //$NON-NLS-1$

	
	/**
	 * This prefix will be concatenated with replacement text to store quickfix
	 * patches in Codan markers. Cf.
	 * {@link fr.jayacode.rapider.checker.cxx.quickfix.MarkerResolution}.
	 */

	/**
	 * This one is used to get the sibling markers, in order to make all the replacement of a single diagnostic at once
	 */
	public static final String DIAGNOSTIC_ID_PREFIX = "rapiderdiagnosticid:"; //$NON-NLS-1$

	public static final String REPLACEMENT_TEXT_PREFIX = "rapiderreplacementtext:"; //$NON-NLS-1$

	private final RapiderInvoker externalToolInvoker;
	private final IInvocationParametersProvider parametersProvider;

	private List<String> problemIds = new ArrayList<String>();

	public Checker() {
		this.parametersProvider = new InvocationParametersProvider();
		this.externalToolInvoker = new RapiderInvoker();
	}

	/**
	 * Returns {@code false} because this checker cannot run "as you type" by
	 * default.
	 * 
	 * @return {@code false}.
	 */
	@Override
	public boolean runInEditor() {
		return false;
	}

	@Override
	public boolean processResource(IResource resource) {
		if (PROCESSED_EXTENSIONS.contains(resource.getFileExtension())) {
			this.process(resource);
		}
		return false;
	}

	private void process(IResource resource) {
		try {
			InvocationParameters parameters = this.parametersProvider.createParameters(resource);
			if (parameters != null) {
				invokeRapider(parameters);
			}
		} catch (Throwable error) {
			logResourceProcessingFailure(error, resource);
		}
	}

	
	/**
	 * Invoke Rapider
	 * @param parameters
	 * @throws Throwable
	 */
	private void invokeRapider(InvocationParameters parameters) throws Throwable {
		try {
			this.externalToolInvoker.invoke(parameters, createErrorParserManager(parameters));
		} catch (InvocationFailure error) {
			handleInvocationFailure(error, parameters);
		}
	}

	private ErrorParserManager createErrorParserManager(InvocationParameters parameters) {
		IProject project = parameters.getActualFile().getProject();
		URI workingDirectory = URIUtil.toURI(parameters.getWorkingDirectory());
		return new ErrorParserManager(project, workingDirectory, this, getParserIDs());
	}

	/**
	 * Handles a failure reported when invoking the external tool. This
	 * implementation simply logs the failure.
	 * 
	 * @param error
	 *            the reported failure.
	 * @param parameters
	 *            the parameters passed to the external tool executable.
	 */
	protected void handleInvocationFailure(InvocationFailure error, InvocationParameters parameters) {
		logResourceProcessingFailure(error, parameters.getActualFile());
	}

	private static void logResourceProcessingFailure(Throwable error, IResource resource) {
		String location = resource.getLocation().toOSString();
		String msg = String.format("Unable to process resource %s", location); //$NON-NLS-1$
		Activator.logError(msg, error);
	}

	@Override
	public void initPreferences(IProblemWorkingCopy problem) {
		super.initPreferences(problem);
		this.problemIds.add(problem.getId());
		getLaunchModePreference(problem).enableInLaunchModes(CheckerLaunchMode.RUN_ON_DEMAND,
				CheckerLaunchMode.RUN_ON_FILE_OPEN, CheckerLaunchMode.RUN_ON_FILE_SAVE);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.cdt.core.IMarkerGenerator#addMarker(org.eclipse.cdt.core.
	 * ProblemMarkerInfo)
	 * 
	 * NB: this method can only handle ProblemMarkerInfo from
	 * fr.jayacode.rapider.checker.cxx.parser.ErrorParser
	 */
	public void addMarker(final ProblemMarkerInfo info) {
		RapiderProblemMarkerInfo castedInfo = (RapiderProblemMarkerInfo) info;
		String diagnosticIdArg = DIAGNOSTIC_ID_PREFIX + castedInfo.getDiagnosticId();
		String replacementTextArg = REPLACEMENT_TEXT_PREFIX + castedInfo.getReplacementText();
		reportProblem(RAPIDER_PROBLEM_ID, createProblemLocation(info), info.description, diagnosticIdArg, replacementTextArg);
	}

	@Deprecated
	@Override
	public void addMarker(IResource file, int lineNumber, String description, int severity, String variableName) {
		// We should not pass here
	}

	protected IProblemLocation createProblemLocation(ProblemMarkerInfo info) {
		IProblemLocationFactory factory = CodanRuntime.getInstance().getProblemLocationFactory();
		return factory.createProblemLocation((IFile) info.file, info.startChar, info.endChar, info.lineNumber);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.eclipse.cdt.codan.core.cxx.externaltool.AbstractExternalToolBasedChecker#
	 * getParserIDs()
	 */
	protected String[] getParserIDs() {
		return new String[] { RAPIDER_PARSER_ID };
	}


	/*
	 * (non-Javadoc) Filtre pour les commentaires de suppression du problème : si la
	 * ligne contient le commentaire, alors on ne remonte pas le problème comme
	 * étant remonté à l'utilisateur
	 * 
	 * @see org.eclipse.cdt.codan.core.model.AbstractCheckerWithProblemPreferences#
	 * shouldProduceProblem(org.eclipse.cdt.codan.core.model.IProblem,
	 * org.eclipse.cdt.codan.core.model.IProblemLocation, java.lang.Object[])
	 */
	@Override
	protected boolean shouldProduceProblem(IProblem problem, IProblemLocation loc, Object... args) {
		String suppressionComment = (String) getSuppressionCommentPreference(problem).getValue();
		if (suppressionComment.isEmpty()) {
			return super.shouldProduceProblem(problem, loc, args);
		}
		IResource resource = loc.getFile();
		if (!(resource instanceof IFile)) {
			return false;
		}
		IFile file = (IFile) resource;
		try {
			InputStream content = file.getContents();
			BufferedReader reader = new BufferedReader(new InputStreamReader(content));
			String line = new String();
			for (int i = 0; i < loc.getLineNumber(); i++) {
				line = reader.readLine();
			}
			return !line.contains(suppressionComment);
		} catch (CoreException | IOException e) {
			// TODO : print stack ?
		}
		return super.shouldProduceProblem(problem, loc, args);
	}

}
