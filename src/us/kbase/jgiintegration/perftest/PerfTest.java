package us.kbase.jgiintegration.perftest;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.jgiintegration.common.JGIOrganismPage;

import com.gargoylesoftware.htmlunit.WebClient;


public class PerfTest {
	
	private static final String QC = "QC Filtered Raw Data";
	private static final String RAW = "Raw Data";

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
	}
	
	@Test
	public void dumpJobsIntoPtKBQueue() throws Exception {
		WebClient cli = new WebClient();
		JGIOrganismPage org = new JGIOrganismPage(cli, "BlaspURHD0036",
				JGI_USER, JGI_PWD);
		List<String> fileGroups = org.listFileGroups();
		if (fileGroups.contains(QC)) {
			push(org, QC);
		}
		if (fileGroups.contains(RAW)) {
			push(org, RAW);
		}
	}
	
	private void push(JGIOrganismPage org, String fileGroup) {
		System.out.println(org.getWorkspaceName("foo"));
		System.out.println(fileGroup);
		
	}

}
