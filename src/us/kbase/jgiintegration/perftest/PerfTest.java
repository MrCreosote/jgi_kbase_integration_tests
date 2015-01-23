package us.kbase.jgiintegration.perftest;

import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.jgiintegration.common.JGIFileLocation;
import us.kbase.jgiintegration.common.JGIOrganismPage;

import com.gargoylesoftware.htmlunit.WebClient;


public class PerfTest {
	
	private static final boolean SKIP_PUSH = true; //for testing
	private static final int NUM_FILES_TO_PUSH = 200;
	
	private static final String QC = "QC Filtered Raw Data";
	private static final String RAW = "Raw Data";
	
	private static final String WIPE_URL = 
			"http://dev03.berkeley.kbase.us:9000";

	private static String JGI_USER;
	private static String JGI_PWD;
	private static String KB_USER_1;
	private static String KB_PWD_1;

	@BeforeClass
	public static void setUpClass() throws Exception {
		Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);
		JGI_USER = System.getProperty("test.jgi.user");
		JGI_PWD = System.getProperty("test.jgi.pwd");
		KB_USER_1 = System.getProperty("test.kbase.user1");
		KB_PWD_1 = System.getProperty("test.kbase.pwd1");
		//TODO wipe server
	}
	
	@Test
	public void dumpJobsIntoPtKBQueue() throws Exception {
		List<PushedFile> pushed = new LinkedList<PerfTest.PushedFile>();
		WebClient cli = new WebClient();
		JGIOrganismPage org = new JGIOrganismPage(cli, "BlaspURHD0036",
				JGI_USER, JGI_PWD);
		List<String> fileGroups = org.listFileGroups();
		if (fileGroups.contains(QC)) {
			pushed.addAll(push(org, QC));
			
		}
		if (fileGroups.contains(RAW)) {
			pushed.addAll(push(org, RAW));
		}
		for (PushedFile file: pushed) {
			System.out.println(file.getWorkspace() + "\t" + file.getFile());
		}
	}
	
	private List<PushedFile> push(JGIOrganismPage org, String fileGroup) throws Exception {
		String workspace = org.getWorkspaceName("foo");
		System.out.println(fileGroup);
		System.out.println(org.listFiles(fileGroup));
		List<PushedFile> ret = new LinkedList<PerfTest.PushedFile>();
		for (String file: org.listFiles(fileGroup)) {
			if (!SKIP_PUSH) {
				org.selectFile(new JGIFileLocation(fileGroup, file));
				org.pushToKBase(KB_USER_1, KB_PWD_1);
			}
			ret.add(new PushedFile(workspace, file));
		}
		return ret;
	}
	
	private static class PushedFile {
		
		private final String workspace;
		private final String file;
		
		public PushedFile(String workspace, String file) {
			super();
			this.workspace = workspace;
			this.file = file;
		}
		
		public String getWorkspace() {
			return workspace;
		}

		public String getFile() {
			return file;
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("PushedFile [workspace=");
			builder.append(workspace);
			builder.append(", file=");
			builder.append(file);
			builder.append("]");
			return builder.toString();
		}
	}

}
