package com.pmease.gitop.core.hookcallback;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.shiro.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.pmease.commons.git.Commit;
import com.pmease.commons.util.StringUtils;
import com.pmease.gitop.core.manager.BranchManager;
import com.pmease.gitop.core.manager.ProjectManager;
import com.pmease.gitop.core.manager.UserManager;
import com.pmease.gitop.model.Branch;
import com.pmease.gitop.model.Project;
import com.pmease.gitop.model.PullRequest;
import com.pmease.gitop.model.User;
import com.pmease.gitop.model.gatekeeper.GateKeeper;
import com.pmease.gitop.model.gatekeeper.checkresult.Accepted;
import com.pmease.gitop.model.gatekeeper.checkresult.CheckResult;
import com.pmease.gitop.model.gatekeeper.checkresult.Rejected;
import com.pmease.gitop.model.permission.ObjectPermission;

@SuppressWarnings("serial")
@Singleton
public class PreReceiveServlet extends CallbackServlet {

	private static final Logger logger = LoggerFactory.getLogger(PreReceiveServlet.class);

	public static final String PATH = "/git-pre-receive";

	private final BranchManager branchManager;

	private final UserManager userManager;
	
	@Inject
	public PreReceiveServlet(ProjectManager projectManager, 
			BranchManager branchManager, UserManager userManager) {
		super(projectManager);
		this.branchManager = branchManager;
		this.userManager = userManager;
	}
	
	private void error(Output output, String... messages) {
		output.markError();
		output.writeLine();
		output.writeLine("*******************************************************");
		output.writeLine("*");
		for (String message: messages)
			output.writeLine("*  " + message);
		output.writeLine("*");
		output.writeLine("*******************************************************");
		output.writeLine();
	}
	
	@Override
	protected void callback(Project project, String callbackData, Output output) {
		List<String> splitted = StringUtils.splitAndTrim(callbackData, " ");
		String refName = splitted.get(2);
		if (refName.startsWith(Project.REFS_GITOP)) {
			if (!SecurityUtils.getSubject().isPermitted(ObjectPermission.ofProjectAdmin(project)))
				error(output, "Only project administrators can update gitop refs.");
		} else {
			String branchName = Branch.getName(refName);
			if (branchName != null) {
				String oldCommitHash = splitted.get(0);
				
				if (!oldCommitHash.equals(Commit.ZERO_HASH)) {
					Branch branch = branchManager.findBy(project, branchName);
					Preconditions.checkNotNull(branch);

					logger.info("Executing pre-receive hook against branch {}...", branchName);
					
					User user = userManager.getCurrent();
					Preconditions.checkNotNull(user);
			
					String newCommitHash = splitted.get(1);

					GateKeeper gateKeeper = project.getCompositeGateKeeper();
					CheckResult checkResult = gateKeeper.checkCommit(user, branch, newCommitHash);
			
					if (!(checkResult instanceof Accepted)) {
						List<String> messages = new ArrayList<>();
						for (String each: checkResult.getReasons())
							messages.add(each);
						if (!newCommitHash.equals(Commit.ZERO_HASH) && !(checkResult instanceof Rejected)) {
							messages.add("");
							messages.add("----------------------------------------------------");
							messages.add("You may submit a pull request instead.");
						}
						error(output, messages.toArray(new String[messages.size()]));
					} else {
						for (PullRequest request: branch.getIngoingRequests()) {
							if (request.isOpen()) {
								error(output, "There are unclosed pull requests targeting this branch.", 
										"Please close them before continue.");
								return;
							}
						}
						for (PullRequest request: branch.getOutgoingRequests()) {
							if (request.isOpen()) {
								error(output, "There are unclosed pull requests originating from this branch.", 
										"Please close them before continue.");
								return;
							}
						}
					}
				}
			}
		}
	}
}
