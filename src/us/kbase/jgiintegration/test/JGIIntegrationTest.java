package us.kbase.jgiintegration.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static us.kbase.jgiintegration.common.JGIUtils.wipeRemoteServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MimeMultipart;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.abstracthandle.AbstractHandleClient;
import us.kbase.abstracthandle.Handle;
import us.kbase.auth.AuthService;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.ServerException;
import us.kbase.common.service.Tuple2;
import us.kbase.jgiintegration.common.JGIFileLocation;
import us.kbase.jgiintegration.common.JGIOrganismPage;
import us.kbase.jgiintegration.common.JGIUtils.WipeException;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.ShockFileInformation;
import us.kbase.shock.client.ShockNode;
import us.kbase.shock.client.ShockNodeId;
import us.kbase.wipedev03.WipeDev03Client;
import us.kbase.workspace.ListObjectsParams;
import us.kbase.workspace.ObjectData;
import us.kbase.workspace.ObjectIdentity;
import us.kbase.workspace.ProvenanceAction;
import us.kbase.workspace.WorkspaceClient;
import us.kbase.workspace.WorkspaceIdentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gargoylesoftware.htmlunit.AlertHandler;
import com.gargoylesoftware.htmlunit.CollectingAlertHandler;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.WebClient;
import com.github.fge.jsonpatch.diff.JsonDiff;


public class JGIIntegrationTest {
	
	//TODO WAIT: may need to parallelize tests. If so print thread ID with all output
	
	//should probably use slf4j instead of print statements, but can't be arsed for now
	
	/* Set to true to write objects to the objects folder set below.
	 * This will overwrite the prior objects, so archive them if necessary
	 * first.
	 */
	private static final boolean SAVE_WS_OBJECTS = false;
	private static final String WS_OBJECTS_FOLDER = "workspace_objects";
	
	private static final String WS_URL =
			"https://dev03.berkeley.kbase.us/services/ws";
	private static final String HANDLE_URL = 
			"https://dev03.berkeley.kbase.us/services/handle_service";
	private static final String WIPE_URL = 
			"http://dev03.berkeley.kbase.us:9000";

	private static final int PUSH_TO_WS_TIMEOUT_SEC = 30 * 60; //30min
	private static final int PUSH_TO_WS_SLEEP_SEC = 5;
	
	//for testing. If you're not wiping the database most likely you need to
	//not test versions either.
	private static final boolean SKIP_WIPE = false;
	private static final boolean SKIP_VERSION_ASSERT = false;
	
	private static final String EXT_JSON = ".json";
	private static final String EXT_META_JSON = ".meta.json";
	private static final String EXT_PROV_JSON = ".prov.json";
	
	private static final String TYPE_READ_PREFIX =
			"KBaseFile.PairedEndLibrary";
	private static final String TYPE_ASSEMBLY_PREFIX =
			"KBaseFile.AssemblyFile";
	private static final String TYPE_ANNOTATION_PREFIX =
			"KBaseFile.AnnotationFile";
	private static final String FILELOC_READ = "lib1";
	private static final String FILELOC_ASSEMBLY = "assembly_file";
	private static final String FILELOC_ANNOTATION = "annotation_file";
	private static final Map<String, String> TYPE_TO_FILELOC =
			new HashMap<String, String>();
	
	static {
		TYPE_TO_FILELOC.put(TYPE_READ_PREFIX, FILELOC_READ);
		TYPE_TO_FILELOC.put(TYPE_ASSEMBLY_PREFIX, FILELOC_ASSEMBLY);
		TYPE_TO_FILELOC.put(TYPE_ANNOTATION_PREFIX, FILELOC_ANNOTATION);
	}
	
	
	private static String JGI_USER;
	private static String JGI_PWD;
	private static String KB_USER_1;
	private static String KB_PWD_1;
	private static String KB_USER_2;
	private static String KB_PWD_2;
	
	private static Folder GMAIL;
	private static String MAIL_SUBJECT_SUCCESS =
			"JGI/KBase data transfer succeeded";
	private static List<String> MAIL_BODY_SUCCESS_START =
			new LinkedList<String>();
	static {
		MAIL_BODY_SUCCESS_START.add("Dear KBase user,");
		MAIL_BODY_SUCCESS_START.add("Your data has been pushed to KBase.");
		MAIL_BODY_SUCCESS_START.add("You can find your imported files at the links below.");
		MAIL_BODY_SUCCESS_START.add("");
	}
	private static List<String> MAIL_BODY_SUCCESS_END =
			new LinkedList<String>(); 
	static {
		MAIL_BODY_SUCCESS_END.add("");
		MAIL_BODY_SUCCESS_END.add("");
		MAIL_BODY_SUCCESS_END.add("Alternately, you can access the data in " +
				"the 'Shared With Me' tab in the Narrative data panel. For " +
				"more information, see the Push to KBase tutorial here: http://kbase.us/transfer-jgi-data/");
		MAIL_BODY_SUCCESS_END.add("If you have any questions, please contact help@kbase.us"); 
		MAIL_BODY_SUCCESS_END.add("JGI-KBase");
	}
	
	private static String MAIL_SUBJECT_FAIL = "JGI/KBase data transfer failed";
	private static String MAIL_BODY_FAIL = 
			"\r\nDear KBase user,\r\n" +
			"\r\n" +
			"An unexpected error occurred while processing your upload request for %s.\r\n" +
			"\r\n" +
			"An email has been sent to the system administrators. If this is urgent, please contact help@kbase.us\r\n" +
			"\r\n" +
			"JGI-KBase";
	
	private static AbstractHandleClient HANDLE_CLI;
	private static WipeDev03Client WIPE;
	
	private static final ObjectMapper SORTED_MAPPER = new ObjectMapper();
	static {
		SORTED_MAPPER.configure(
				SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	}
	
	@BeforeClass
	public static void setUpClass() throws Exception {
		Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);
		if (SAVE_WS_OBJECTS) {
			System.out.println("Saving workspace objects to git repo");
		}
		if (SKIP_WIPE) {
			System.out.println("Skipping wipe of test data stores");
		}
		if (SKIP_VERSION_ASSERT) {
			System.out.println("Not testing object versions");
		}
		JGI_USER = System.getProperty("test.jgi.user");
		JGI_PWD = System.getProperty("test.jgi.pwd");
		KB_USER_1 = System.getProperty("test.kbase.user1");
		KB_PWD_1 = System.getProperty("test.kbase.pwd1");
		KB_USER_2 = System.getProperty("test.kbase.user2");
		KB_PWD_2 = System.getProperty("test.kbase.pwd2");
		String gmailuser = System.getProperty("test.kbase.jgi.gmail.user");
		String gmailpwd = System.getProperty("test.kbase.jgi.gmail.pwd");
		
		Session session = Session.getInstance(new Properties());
		Store store = session.getStore("imaps");
		store.connect("imap.gmail.com", gmailuser, gmailpwd);
		GMAIL = store.getFolder("inbox");
		
		HANDLE_CLI = new AbstractHandleClient(
				new URL(HANDLE_URL), KB_USER_1, KB_PWD_1);
		HANDLE_CLI.setIsInsecureHttpConnectionAllowed(true);
		HANDLE_CLI.setAllSSLCertificatesTrusted(true);
		
		String wipeUser = System.getProperty("test.kbase.wipe_user");
		String wipePwd = System.getProperty("test.kbase.wipe_pwd");
		if (!SKIP_WIPE) {
			WIPE = wipeRemoteServer(new URL(WIPE_URL), wipeUser, wipePwd);
		}
	}
	
	@AfterClass
	public static void cleanUpClass() throws Exception {
		// Check if the WS server is down after the tests
		// if so then we should restart
		System.out.println("Checking Workspace server status...");
		try {
			WorkspaceClient wsCli = new WorkspaceClient(new URL(WS_URL));
			wsCli.setIsInsecureHttpConnectionAllowed(true);
			wsCli.setAllSSLCertificatesTrusted(true);
			wsCli.setConnectionReadTimeOut(5000);
			System.out.println("Workspace is still running, version is: "
					+ wsCli.ver());
		} catch (Exception e) {
			// we assume any exception means the server is down.
			System.out.println("The Workspace service seems to be down, " +
					"attempting a restart. Exception was: ");
			e.printStackTrace();
			Tuple2<Long, String> w = WIPE.restartWorkspace();
			if (w.getE1() > 0 ) {
				throw new WipeException(
						"Restart of the test server failed. The wipe server said:\n"
								+ w.getE2());
			}
			System.out.println("Restart was successful. Server said:\n" +
					w.getE2());
		}
	}
	
	private static class FileSpec {
		private final JGIFileLocation location;
		private final String type;
		private final long expectedVersion;
		private final String shockMD5;

		/** note the workspace dummy MD5 is the workspace object with the
		 * variable handle contents replaced by the string "dummy" 
		 */
		public FileSpec(JGIFileLocation location, String type,
				long expectedVersion, String shockMD5) {
			super();
			this.location = location;
			this.type = type;
			this.expectedVersion = expectedVersion;
			this.shockMD5 = shockMD5;
		}

		public JGIFileLocation getLocation() {
			return location;
		}

		public String getType() {
			return type;
		}

		public long getExpectedVersion() {
			return expectedVersion;
		}

		public String getShockMD5() {
			return shockMD5;
		}
		
		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("FileSpec [location=");
			builder.append(location);
			builder.append(", type=");
			builder.append(type);
			builder.append(", expectedVersion=");
			builder.append(expectedVersion);
			builder.append(", shockMD5=");
			builder.append(shockMD5);
			builder.append("]");
			return builder.toString();
		}
	}
	
	private static class TestSpec {
		private final String organismCode;
		private final String kbaseUser;
		private final String kbasePassword;
		private final List<FileSpec> filespecs =
				new LinkedList<JGIIntegrationTest.FileSpec>();
		private final List<FileSpec> unselect =
				new LinkedList<JGIIntegrationTest.FileSpec>();
		
		public TestSpec(String organismCode, String kbaseUser,
				String kbasePassword) {
			if (organismCode == null) {
				throw new NullPointerException(organismCode);
			}
			if (kbaseUser == null) {
				throw new NullPointerException(kbaseUser);
			}
			if (kbasePassword == null) {
				throw new NullPointerException(kbasePassword);
			}
			this.organismCode = organismCode;
			this.kbaseUser = kbaseUser;
			this.kbasePassword = kbasePassword;
		}
		
		public void addFileSpec(FileSpec spec) {
			addFileSpec(spec, false);
		}
		
		public void addFileSpec(FileSpec spec, boolean unselect) {
			filespecs.add(spec);
			if (unselect) {
				this.unselect.add(spec);
			}
		}
		
		public String getOrganismCode() {
			return organismCode;
		}
		
		public String getKBaseUser() {
			return kbaseUser;
		}
		
		public String getKBasePassword() {
			return kbasePassword;
		}

		public List<FileSpec> getFilespecs() {
			return new LinkedList<FileSpec>(filespecs);
		}
		
		public List<FileSpec> getFilespecsToUnselect() {
			return new LinkedList<FileSpec>(unselect);
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("TestSpec [organismCode=");
			builder.append(organismCode);
			builder.append(", kbaseUser=");
			builder.append(kbaseUser);
			builder.append(", kbasePassword=");
			builder.append(kbasePassword);
			builder.append(", filespecs=");
			builder.append(filespecs);
			builder.append(", unselect=");
			builder.append(unselect);
			builder.append("]");
			return builder.toString();
		}
	}
	
	private static class TestResult {
		private final String shockID;
		private final String shockURL;
		private final String handleID;
		private final String workspaceName;
		
		public TestResult(String workspaceName, String shockID,
				String shockURL, String handleID) {
			super();
			this.shockID = shockID;
			this.shockURL = shockURL;
			this.handleID = handleID;
			this.workspaceName = workspaceName;
		}

		public String getShockID() {
			return shockID;
		}

		public String getShockURL() {
			return shockURL;
		}

		@SuppressWarnings("unused")
		public String getHandleID() {
			return handleID;
		}
		
		public String getWorkspaceName() {
			return workspaceName;
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("TestResult [shockID=");
			builder.append(shockID);
			builder.append(", shockURL=");
			builder.append(shockURL);
			builder.append(", handleID=");
			builder.append(handleID);
			builder.append(", workspaceName=");
			builder.append(workspaceName);
			builder.append("]");
			return builder.toString();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result
					+ ((handleID == null) ? 0 : handleID.hashCode());
			result = prime * result
					+ ((shockID == null) ? 0 : shockID.hashCode());
			result = prime * result
					+ ((shockURL == null) ? 0 : shockURL.hashCode());
			result = prime * result
					+ ((workspaceName == null) ? 0 : workspaceName.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			TestResult other = (TestResult) obj;
			if (handleID == null) {
				if (other.handleID != null)
					return false;
			} else if (!handleID.equals(other.handleID))
				return false;
			if (shockID == null) {
				if (other.shockID != null)
					return false;
			} else if (!shockID.equals(other.shockID))
				return false;
			if (shockURL == null) {
				if (other.shockURL != null)
					return false;
			} else if (!shockURL.equals(other.shockURL))
				return false;
			if (workspaceName == null) {
				if (other.workspaceName != null)
					return false;
			} else if (!workspaceName.equals(other.workspaceName))
				return false;
			return true;
		}
	}
	
	@SuppressWarnings("serial")
	private static class TestException extends RuntimeException {

		public TestException(String msg) {
			super(msg);
		}
	}
	
	@Before
	public void beforeTest() {
		System.out.println("----------------starting test-----------------");
	}
	
	@After
	public void afterTest() {
		System.out.println("--------------- completed test----------------\n");
	}
	
	@Test 
	public void pushFailedEmail() throws Exception {
		int emailTimeoutSec = 30 * 60;
		
		System.out.print("Shutting down test workspace... ");
		Tuple2<Long, String> w = WIPE.shutDownWorkspace();
		if (w.getE1() > 0 ) {
			throw new WipeException(
					"Shutdown of the test server failed. The wipe server said:\n" +
							w.getE2());
		}
		System.out.println("done. Server said:\n" + w.getE2());
		
		Date start = new Date();
		try {
			TestSpec tspec = new TestSpec("BurspATCC52813", KB_USER_1,
					KB_PWD_1);
			tspec.addFileSpec(new FileSpec(
					new JGIFileLocation("QC and Genome Assembly",
							"final.assembly.fasta"),
							"KBaseFile.PairedEndLibrary-2.1", 1L,
							"5c66abbb2515674a074d2a41ecf01017"));
			WebClient cli = new WebClient();
			List<String> alerts = new LinkedList<String>();
			String wsName = processTestSpec(tspec, cli,
					new CollectingAlertHandler(alerts), false);
			System.out.println(String.format(
					"Finished push at UI level at %s for test %s",
					new Date(), getTestMethodName()));

			String body = getPtKBEmailBody(emailTimeoutSec, false);
			String expectedBody = String.format(MAIL_BODY_FAIL, wsName);
			assertThat("got correct failure email", body, is(expectedBody));

			cli.closeAllWindows();

		} catch (Exception e) {
			System.out.println("Caught an exception while the workspace was " +
					"down. Restarting workspace and throwing original " + 
					"exception.");
			w = WIPE.restartWorkspace();
			if (w.getE1() > 0) {
				System.out.println("Restart of the test server failed. " +
						"The wipe server said:" + w.getE2());
			}
			throw e;
		}

		System.out.print("Restarting test workspace... ");
		w = WIPE.restartWorkspace();
		if (w.getE1() > 0 ) {
			throw new WipeException(
					"Restart of the test server failed. The wipe server said:\n" +
							w.getE2());
		}
		System.out.println("done. Server said:\n" + w.getE2());
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
	}
	
	@Test
	public void pushSingleFile() throws Exception {
		TestSpec tspec = new TestSpec("BlaspURHD0036", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"7625.2.79179.AGTTCC.adnq.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"5c66abbb2515674a074d2a41ecf01017"));
		runTest(tspec);
	}
	
	@Test
	public void pushSingleFileDeleteShockNodeAndRepush() throws Exception {
		/* Tests JGI code that memoizes shocknodes for files that have been
		 * previously pushed.
		 */
		TestSpec tspec = new TestSpec("MarpieDSM16108", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"9364.7.131005.CTAGCT.anqdp.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo"));
		TestResult tr = runTest(tspec).get(tspec.getFilespecs().get(0));
		
		AuthToken token = AuthService.login(tspec.getKBaseUser(),
				tspec.getKBasePassword()).getToken();
		BasicShockClient cli = new BasicShockClient(new URL(tr.getShockURL()),
				token);
		cli.getNode(new ShockNodeId(tr.getShockID())).delete();
		
		TestSpec tspec2 = new TestSpec("MarpieDSM16108", KB_USER_1, KB_PWD_1);
		tspec2.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"9364.7.131005.CTAGCT.anqdp.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 2L,
						"6bd062af06cf31f73eea9906bbe6ae85"));
		runTest(tspec2);
	}
	
	@Test
	public void pushAssembly() throws Exception {
		TestSpec tspec = new TestSpec("LutspHel_I_33_5", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC and Genome Assembly",
						"final.assembly.fasta"),
						"KBaseFile.AssemblyFile-2.1", 1L,
						"934f168f5e5a016e16efde3097c2632a"));
		runTest(tspec);
	}
	
	@Test
	public void rejectAnnotation() throws Exception {
		TestSpec tspec = new TestSpec("ThaarcSCAB663P07", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("IMG Data",
						"14052.assembled.gff",
						true), //expect rejection
						"KBaseFile.AnnotationFile-2.1", 1L,
						"04c5df5bb396cb80842befb9ff47e35b"));
		runTest(tspec);
	}
	
	//restore when we push annotations again
//	@Test
//	public void pushAnnotation() throws Exception {
//		TestSpec tspec = new TestSpec("ThaarcSCAB663P07", KB_USER_1, KB_PWD_1);
//		tspec.addFileSpec(new FileSpec(
//				new JGIFileLocation("IMG Data",
//						"14052.assembled.gff"),
//						"KBaseFile.AnnotationFile-2.1", 1L,
//						"04c5df5bb396cb80842befb9ff47e35b"));
//		runTest(tspec);
//	}
	
	@Test
	public void pushTwoFiles() throws Exception {
		TestSpec tspec = new TestSpec("AlimarDSM23064", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6501.2.45840.GCAAGG.adnq.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"f0b44aae6c1714965dd345f368c7927a"));
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("Raw Data",
						"6501.2.45840.GCAAGG.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"ff117914a28ffa48b520707e89fa683c"));
		runTest(tspec);
	}
	
	/*
	 * Looked through 100 projects for 2 pushable files in 1 group, but didn't
	 * find any. If this project becomes visible again or I stumble across a
	 * project with multiple pushable files per group I'll reinstate.
	 */
//	@Test
//	public void pushTwoFilesSameGroup() throws Exception {
//		TestSpec tspec = new TestSpec("ColspSCAC281C22", KB_USER_1, KB_PWD_1);
//		tspec.addFileSpec(new FileSpec(
//				new JGIFileLocation("QC Filtered Raw Data",
//						"6622.1.49213.GTCCGC.adnq.fastq.gz"),
//						"KBaseFile.PairedEndLibrary-2.1", 1L,
//						"9e4d728e9e676086fb8f30c4f093274d"));
//		tspec.addFileSpec(new FileSpec(
//				new JGIFileLocation("QC Filtered Raw Data",
//						"8440.1.101057.AGTCA.anqdp.fastq.gz"),
//						"KBaseFile.PairedEndLibrary-2.1", 1L,
//						"5cb8fd67fa7514468daf560d2ce679fc"));
//		runTest(tspec);
//		
//	}
	
	@Test
	public void pushSameFileWithSameClient() throws Exception {
		FileSpec fs1 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6787.4.54588.CTTGTA.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"b9a27bd18400c8c16285da69048fe15f");
		
		FileSpec fs2 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6787.4.54588.CTTGTA.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 2L,
				"b9a27bd18400c8c16285da69048fe15f");
		
		TestSpec tspec1 = new TestSpec("CanThiBermud0003", KB_USER_1, KB_PWD_1);
		tspec1.addFileSpec(fs1);
		
		TestSpec tspec2 = new TestSpec("CanThiBermud0003", KB_USER_1, KB_PWD_1);
		tspec2.addFileSpec(fs2);
		
		System.out.println("Starting test " + getTestMethodName());
		Date start = new Date();
		WebClient cli = new WebClient();
		List<String> alerts = new LinkedList<String>();
		String wsName = processTestSpec(tspec1, cli,
				new CollectingAlertHandler(alerts), false);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 1",
				new Date(), getTestMethodName()));
		
		Map<FileSpec, TestResult> res1 = checkResults(tspec1, wsName);
		
		processTestSpec(tspec2, cli, new CollectingAlertHandler(alerts), true);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 2",
				new Date(), getTestMethodName()));
		
		Map<FileSpec, TestResult> res2 = checkResults(tspec2, wsName);
		
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		assertThat("Pushing same file twice uses same shock node",
				res2.get(fs2), is(res1.get(fs1)));
	}
	
	@Test
	public void pushSameFileWithDifferentClient() throws Exception {
		FileSpec fs1 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"8446.4.101451.AGAAGA.anqdp.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"26306e5cc8f3178713df5e2f9594c894");
		
		FileSpec fs2 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"8446.4.101451.AGAAGA.anqdp.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 2L,
				"26306e5cc8f3178713df5e2f9594c894");
		
		TestSpec tspec1 = new TestSpec("ActgenspDSM45722", KB_USER_1, KB_PWD_1);
		tspec1.addFileSpec(fs1);
		
		TestSpec tspec2 = new TestSpec("ActgenspDSM45722", KB_USER_1, KB_PWD_1);
		tspec2.addFileSpec(fs2);
		
		System.out.println("Starting test " + getTestMethodName());
		Date start = new Date();
		WebClient cli = new WebClient();
		List<String> alerts = new LinkedList<String>();
		String wsName = processTestSpec(tspec1, cli,
				new CollectingAlertHandler(alerts), false);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 1",
				new Date(), getTestMethodName()));
		
		Map<FileSpec, TestResult> res1 = checkResults(tspec1, wsName);
		
		cli = new WebClient(); //this is the only major difference from the same client test
		processTestSpec(tspec2, cli, new CollectingAlertHandler(alerts), false);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 2",
				new Date(), getTestMethodName()));
		
		Map<FileSpec, TestResult> res2 = checkResults(tspec2, wsName);
		
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		assertThat("Pushing same file twice uses same shock node",
				res2.get(fs2), is(res1.get(fs1)));
	}
	
	@Test
	public void pushSameFileDifferentUsers() throws Exception {
		FileSpec fs1 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6133.1.38460.TGCTGG.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"7952ee14bef7eb5d5aa55f41ff40dab7");
		
		FileSpec fs2 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6133.1.38460.TGCTGG.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"7952ee14bef7eb5d5aa55f41ff40dab7");
		
		TestSpec tspec1 = new TestSpec("BacspJ001005J19_2", KB_USER_1,
				KB_PWD_1);
		tspec1.addFileSpec(fs1);
		
		TestSpec tspec2 = new TestSpec("BacspJ001005J19_2", KB_USER_2,
				KB_PWD_2);
		tspec2.addFileSpec(fs2);
		
		System.out.println("Starting test " + getTestMethodName());
		Date start = new Date();
		WebClient cli = new WebClient();
		List<String> alerts = new LinkedList<String>();
		String wsName = processTestSpec(tspec1, cli,
				new CollectingAlertHandler(alerts), false);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 1",
				new Date(), getTestMethodName()));
		
		Map<FileSpec, TestResult> res1 = checkResults(tspec1, wsName);
		
		wsName = processTestSpec(tspec2, cli, new CollectingAlertHandler(alerts), true);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 2",
				new Date(), getTestMethodName()));
		
		Map<FileSpec, TestResult> res2 = checkResults(tspec2, wsName);
		
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is(true));
		assertThat("Pushing same file twice uses same shock node",
				res2.get(fs2), is(res1.get(fs1)));
	}
	
	@Test
	public void pushNothing() throws Exception {
		TestSpec tspec = new TestSpec("BlaspURHD0036", KB_USER_1, KB_PWD_1); //if parallelize, change to unused page
		List<String> alerts = new LinkedList<String>();
		try {
			runTest(tspec, new CollectingAlertHandler(alerts));
			fail("Pushed without files selected");
		} catch (ElementNotFoundException enfe) {
			assertThat("Correct exception for alert test", enfe.getMessage(),
					is("elementName=[form] attributeName=[name] attributeValue=[form]"));
		}
		Thread.sleep(1000); // wait for alert to finish
		assertThat("Only one alert triggered", alerts.size(), is(1));
		assertThat("Correct alert", alerts.get(0),
				is("No JAMO files were selected for Push to KBase. Please use the checkboxes to select some files!"));
	}
	
	@Test
	public void unselectAndPushNothing() throws Exception {
		TestSpec tspec = new TestSpec("BlaspURHD0036", KB_USER_1, KB_PWD_1); //if parallelize, change to unused page
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"7625.2.79179.AGTTCC.adnq.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"5c66abbb2515674a074d2a41ecf01017"),
				true); //unselect after selecting
		List<String> alerts = new LinkedList<String>();
		try {
			runTest(tspec, new CollectingAlertHandler(alerts));
			fail("Pushed without files selected");
		} catch (ElementNotFoundException enfe) {
			assertThat("Correct exception for alert test", enfe.getMessage(),
					is("elementName=[form] attributeName=[name] attributeValue=[form]"));
		}
		Thread.sleep(1000); // wait for alert to finish
		assertThat("Only one alert triggered", alerts.size(), is(1));
		assertThat("Correct alert", alerts.get(0),
				is("No JAMO files were selected for Push to KBase. Please use the checkboxes to select some files!"));
	}
	
	@Test
	public void unselectAndPushOne() throws Exception {
		TestSpec tspec = new TestSpec("GeobraDSM44526", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"8446.4.101451.ACGATA.anqdp.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo"),
				true); //unselect after selecting
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("Raw Data",
						"8446.4.101451.ACGATA.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"04cc65af00b0b0cd0afc91b002798fb1"));
		String wsName = runTest(tspec).get(tspec.getFilespecs().get(0))
				.getWorkspaceName();
		
		WorkspaceClient wsCli = new WorkspaceClient(
				new URL(WS_URL), KB_USER_1, KB_PWD_1);
		wsCli.setIsInsecureHttpConnectionAllowed(true);
		wsCli.setAllSSLCertificatesTrusted(true);
		assertThat("Only one object in workspace",
				wsCli.listObjects(new ListObjectsParams()
						.withWorkspaces(Arrays.asList(wsName))).size(), is(1));
	}
	
	@Test
	public void rejectOneFile() throws Exception {
		TestSpec tspec = new TestSpec("RosstaDSM19981_2", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC and Genome Assembly",
						"QC.finalReport.pdf",
						true), //expect rejection
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo1")
				);
		runTest(tspec);
	}
	
	@Test
	public void rejectOnePushOne() throws Exception {
		TestSpec tspec = new TestSpec("AllhisDSM15230", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC and Genome Assembly",
						"8327.8.98186.CTAGCT.artifact.clean.fastq.gz",
						true), //expect rejection
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo")
				);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"8327.8.98186.CTAGCT.anqdp.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"a4d84286988f9c85aa6c7f0e4feee81b"));
		
		
		String wsName = runTest(tspec).get(tspec.getFilespecs().get(0))
				.getWorkspaceName();
		
		WorkspaceClient wsCli = new WorkspaceClient(
				new URL(WS_URL), KB_USER_1, KB_PWD_1);
		wsCli.setIsInsecureHttpConnectionAllowed(true);
		wsCli.setAllSSLCertificatesTrusted(true);
		assertThat("Only one object in workspace",
				wsCli.listObjects(new ListObjectsParams()
						.withWorkspaces(Arrays.asList(wsName))).size(), is(1));
	}
	
	private Map<FileSpec, TestResult> runTest(TestSpec tspec)
			throws Exception {
		List<String> alerts = new LinkedList<String>();
		Map<FileSpec, TestResult> res =
				runTest(tspec, new CollectingAlertHandler(alerts));
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		return res;
	}

	private Map<FileSpec, TestResult> runTest(
			TestSpec tspec, AlertHandler handler)
			throws Exception {
		System.out.println("Starting test " + getTestMethodName());
		Date start = new Date();
		WebClient cli = new WebClient();
		String wsName = processTestSpec(tspec, cli, handler, false);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s",
				new Date(), getTestMethodName()));
		
		Map<FileSpec, TestResult> res = checkResults(tspec, wsName);
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		return res;
	}

	private String processTestSpec(TestSpec tspec, WebClient cli,
			AlertHandler handler, boolean skipLogin)
			throws Exception {
		cli.setAlertHandler(handler);
		
		JGIOrganismPage org;
		if (skipLogin) {
			org = new JGIOrganismPage(cli, tspec.getOrganismCode(), null,
					null);
		} else {
			org = new JGIOrganismPage(cli, tspec.getOrganismCode(),
					JGI_USER, JGI_PWD);
		}
		
		for (FileSpec fs: tspec.getFilespecs()) {
			org.selectFile(fs.getLocation());
		}
		
		for (FileSpec fs: tspec.getFilespecsToUnselect()) {
			org.selectFile(fs.getLocation(), false);
		}
		
		if (!GMAIL.isOpen()) {
			GMAIL.open(Folder.READ_WRITE);
		}
		for (Message m: GMAIL.getMessages()) {
			m.setFlag(Flags.Flag.DELETED, true);
		}
		GMAIL.expunge();
		
		org.pushToKBase(tspec.getKBaseUser(), tspec.getKBasePassword());
		return org.getWorkspaceName(tspec.getKBaseUser());
	}

	private Map<FileSpec, TestResult> checkResults(
			TestSpec tspec,
			String workspace)
			throws Exception {
		System.out.println("Checking result in workspace " + workspace);
		Map<FileSpec,TestResult> res = new HashMap<FileSpec, TestResult>();
		Long start = System.nanoTime();
		ObjectData wsObj = null;
		
		WorkspaceClient wsClient = new WorkspaceClient(new URL(WS_URL),
				tspec.getKBaseUser(), tspec.getKBasePassword());
		wsClient.setIsInsecureHttpConnectionAllowed(true);
		wsClient.setAllSSLCertificatesTrusted(true);
		
		
		for (FileSpec fs: tspec.getFilespecs()) {
			if (tspec.getFilespecsToUnselect().contains(fs)) {
				continue;
			}
			if (fs.getLocation().isExpectedRejection()) {
				continue;
			}
			while(wsObj == null) {
				String fileName = fs.getLocation().getFile();
				checkTimeout(start, PUSH_TO_WS_TIMEOUT_SEC,
						String.format(
						"Timed out attempting to access object %s with version %s in workspace %s after %s sec",
						fileName, fs.getExpectedVersion(), workspace,
						PUSH_TO_WS_TIMEOUT_SEC));
				try {
					wsObj = wsClient.getObjects(Arrays.asList(
							new ObjectIdentity()
									.withWorkspace(workspace)
									.withName(fileName)))
							.get(0);
					if (wsObj.getInfo().getE5() < fs.getExpectedVersion()) {
						wsObj = null;
					}
				} catch (ServerException se) {
					if (se.getMessage() == null) {
						System.out.println(String.format(
								"Got null pointer in server exception at %s",
								new Date()));
						System.out.println(se.getData());
						throw se;
					}
					String e1 = String.format(
							"Object %s cannot be accessed: User %s may not read workspace %s",
							fs.getLocation().getFile(), tspec.getKBaseUser(), 
							workspace);
					if (!se.getMessage().equals(e1)) {
						checkErrorAcceptable(fs, workspace, se.getMessage());
					} //otherwise try again
					Thread.sleep(PUSH_TO_WS_SLEEP_SEC * 1000);
				}
			}
			System.out.println(String.format(
					"Retrived file from workspace after %s seconds",
					((System.nanoTime() - start) / 1000000000)));
			Map<String, String> wsmeta = wsClient.getWorkspaceInfo(
					new WorkspaceIdentity().withWorkspace(workspace)).getE9();
			Map<String, String> expectedMeta = new HashMap<String, String>();
			expectedMeta.put("show_in_narrative_data_panel", "1");
			assertThat("correct workspace metadata", wsmeta, is(expectedMeta));
			res.put(fs, checkResults(wsObj, tspec, fs));
			wsObj = null;
		}
		
		assertNoIllegalFilesPushed(tspec, workspace, wsClient);
		checkEmail(workspace, tspec);
		return res;
	}

	private void assertNoIllegalFilesPushed(TestSpec tspec, String workspace,
			WorkspaceClient wsClient) throws Exception {
		for (FileSpec fs: tspec.getFilespecs()) {
			if (fs.getLocation().isExpectedRejection()) {
				try {
					wsClient.getObjects(Arrays.asList(
							new ObjectIdentity()
									.withWorkspace(workspace)
									.withName(fs.getLocation().getFile())))
							.get(0);
					fail(String.format("Illegal file %s pushed",
							fs.getLocation()));
				} catch (ServerException se) {
					if (se.getMessage() == null) {
						System.out.println(
								"Got null pointer in server exception");
						System.out.println(se.getData());
						throw se;
					}
					checkErrorAcceptable(fs, workspace, se.getMessage());
				}
			}
		}
	}

	private void checkErrorAcceptable(FileSpec fs, String workspace,
			String message) {
		String e1 = String.format(
				"Object %s cannot be accessed: No workspace with name %s exists",
				fs.getLocation().getFile(), workspace);
		String e2 = String.format(
				"No object with name %s exists in workspace",
				fs.getLocation().getFile());
				
		if (message.equals(e1)) {
			return; //ok
		}
		if (message.startsWith(e2)) {
			return; //ok
		}
		System.out.println(String.format(
				"Got unnacceptable exception at %s:", new Date()));
		System.out.println(message);
		fail("got unacceptable exception from workspace: " + message);
	}

	private TestResult checkResults(
			ObjectData wsObj, TestSpec tspec, FileSpec fs)
			throws Exception {
		String fileContainerName = getFileContainerName(fs.getType());
		System.out.println(String.format("checking file " + fs.getLocation()));
		@SuppressWarnings("unchecked")
		Map<String, Object> data = wsObj.getData().asClassInstance(Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> fileContainer =
				(Map<String, Object>) data.get(fileContainerName);
		@SuppressWarnings("unchecked")
		Map<String, Object> file =
				(Map<String, Object>) fileContainer.get("file");
		String hid = (String) file.get("hid");
		String shockID = (String) file.get("id");
		String url = (String) file.get("url");
		file.put("hid", "dummy");
		file.put("id", "dummy");
		file.put("url", "dummy");
		Map<String,String> meta = wsObj.getInfo().getE11();
		String wsName = wsObj.getInfo().getE8();
		List<ProvenanceAction> prov = wsObj.getProvenance();
		
		if (SAVE_WS_OBJECTS) {
			writeObjectAsJsonToFile(data, tspec, fs, EXT_JSON);
			writeObjectAsJsonToFile(meta, tspec, fs, EXT_META_JSON);
			writeObjectAsJsonToFile(prov, tspec, fs, EXT_PROV_JSON);
		}
		
		List<JsonNode> diffs = checkWorkspaceData(tspec, fs, data, meta, prov);
		JsonNode datadiff = diffs.get(0);
		JsonNode metadiff = diffs.get(1);
		JsonNode provdiff = diffs.get(2);
		
		Handle h = HANDLE_CLI.hidsToHandles(Arrays.asList(hid)).get(0);

		AuthToken token = AuthService.login(tspec.getKBaseUser(),
				tspec.getKBasePassword()).getToken();
		BasicShockClient shock = new BasicShockClient(new URL(url), token,
				true);
		ShockNode node = shock.getNode(new ShockNodeId(shockID));

		System.out.println("got shock MD5: " +
				node.getFileInformation().getChecksum("md5"));

		assertThat("no changes in workspace object", datadiff.size(),
				is(0));
		assertThat("no changes in workspace object metadata",
				metadiff.size(), is(0));
		assertThat("no changes in workspace object provenance",
				provdiff.size(), is(0));
		assertThat("object type correct", wsObj.getInfo().getE3(),
				is(fs.getType()));
		if (!SKIP_VERSION_ASSERT) {
			assertThat("correct version of object", wsObj.getInfo().getE5(),
					is(fs.getExpectedVersion()));
		}
		assertThat("no error from handle service", wsObj.getHandleStacktrace(),
				is((String) null));
		assertThat("handle type correct", h.getType(), is("shock"));
		assertThat("handle hid correct", h.getHid(), is(hid));
		assertThat("handle shock id correct", h.getId(), is(shockID));
		assertThat("handle url correct", h.getUrl(), is(url));
		//can't check ACLs, can only check that file is accessible
		//need to be owner to see ACLs
		ShockFileInformation sf = node.getFileInformation();
		assertThat("Shock file md5 correct", sf.getChecksum("md5"),
				is(fs.getShockMD5()));
		assertThat("Shock filename correct", sf.getName(),
				is(fs.getLocation().getFile()));
		
		return new TestResult(wsName, shockID, url, hid);
	}
	
	private void checkEmail(String ws, TestSpec tspec) throws Exception {
		int timeoutSec = 10 * 60;
		
		int filecount = 0;
		for (FileSpec fs: tspec.getFilespecs()) {
			if (!fs.getLocation().isExpectedRejection() &&
					!tspec.getFilespecsToUnselect().contains(fs)) {
				filecount++;
			}
		}
		if (filecount == 0) {
			return;
		}
		
		String body = getPtKBEmailBody(timeoutSec, true);
		checkEmailBody(ws, tspec, body);
	}

	private String getPtKBEmailBody(int timeoutSec, boolean success)
			throws MessagingException, IOException, InterruptedException {
		String subject = success ? MAIL_SUBJECT_SUCCESS : MAIL_SUBJECT_FAIL;
		String body = null;
		Long start = System.nanoTime();
		
		while(body == null) {
			checkTimeout(start, timeoutSec,
					String.format(
					"Timed out attempting to retrieve push " + 
					(success ? "success" : "fail") + " email after %s sec",
					timeoutSec));
			if (!GMAIL.isOpen()) {
				GMAIL.open(Folder.READ_WRITE);
			}
			for (Message m: GMAIL.getMessages()) {
				if (body == null) {
					if (m.getSubject().equals(subject)) {
						MimeMultipart mm = (MimeMultipart) m.getContent();
						body = mm.getBodyPart(0).getContent().toString();
					}
				}
				m.setFlag(Flags.Flag.DELETED, true); //clear the inbox after each test
			}
			GMAIL.expunge();
			Thread.sleep(PUSH_TO_WS_SLEEP_SEC * 1000);
		}
		System.out.println(String.format(
				"Retrived success email after %s seconds",
				((System.nanoTime() - start) / 1000000000)));
		return body;
	}

	private void checkEmailBody(String ws, TestSpec tspec, String body) {
		Map<String, String> expectedUrls = new HashMap<String, String>(); 
		for (FileSpec fs: tspec.getFilespecs()) { //should probably make this a method in testspec
			if (!fs.getLocation().isExpectedRejection() &&
					!tspec.getFilespecsToUnselect().contains(fs)) {
				expectedUrls.put(fs.getLocation().getFile(),
						"https://narrative.kbase.us/functional-site/#/jgi/import/" +
								ws + "/" + fs.getLocation().getFile());
			}
		}
		String[] emailLines = body.split("\r\n");
		
		int startlines = MAIL_BODY_SUCCESS_START.size();
		int endlines = MAIL_BODY_SUCCESS_END.size();
		List<String> emailStart = Arrays.asList(Arrays.copyOfRange(
				emailLines, 0, startlines));
		assertThat("correct email start recieved", emailStart,
				is(MAIL_BODY_SUCCESS_START));

		List<String> emailEnd = Arrays.asList(Arrays.copyOfRange(
				emailLines, emailLines.length - endlines, emailLines.length));
		assertThat("correct email end recieved", emailEnd,
				is(MAIL_BODY_SUCCESS_END));
		
		Map<String, String> rcvdUrls = new HashMap<String, String>();
		List<String> urls = Arrays.asList(Arrays.copyOfRange(
				emailLines, startlines, emailLines.length - endlines));
		assertThat("url line count multiple of 3", urls.size() % 3, is(0));
		for (int i = 0; i < urls.size(); i += 3) {
			rcvdUrls.put(urls.get(i), urls.get(i + 1));
			assertThat("blank line between urls", urls.get(i + 2), is(""));
			
		}
		assertThat("correct email urls", rcvdUrls, is(expectedUrls));
	}

	private String getFileContainerName(String type) {
		for (String typePrefix: TYPE_TO_FILELOC.keySet()) {
			if (type.startsWith(typePrefix)) {
				return TYPE_TO_FILELOC.get(typePrefix);
			}
		}
		throw new TestException("Unsupported type: " + type);
	}

	private List<JsonNode> checkWorkspaceData(TestSpec tspec, FileSpec fs,
			Map<String, Object> wsdata, Map<String, String> wsmeta,
			List<ProvenanceAction> prov)
			throws Exception {
		Map<String, Object> expectedData = loadWorkspaceObject(tspec, fs);
		Map<String, String> expectedMeta = loadWorkspaceObjectMeta(tspec, fs);
		List<ProvenanceAction> expectedProv =
				loadWorkspaceObjectProvenance(tspec, fs);
		
		JsonNode datadiff = checkDataEquivalent(expectedData, wsdata,
				tspec, fs, "Workspace object", EXT_JSON);
		JsonNode metadiff = checkDataEquivalent(expectedMeta, wsmeta,
				tspec, fs, "Workspace object metadata", EXT_META_JSON);
		JsonNode provdiff = checkDataEquivalent(expectedProv, prov,
				tspec, fs, "Workspace object provenance", EXT_PROV_JSON);
		
		return Arrays.asList(datadiff, metadiff, provdiff);
	}

	private JsonNode checkDataEquivalent(Object expectedData, Object data,
			TestSpec tspec, FileSpec fs, String name, String ext) {
		ObjectMapper mapper = new ObjectMapper();
		JsonNode datadiff = JsonDiff.asJson(mapper.valueToTree(expectedData), 
				mapper.valueToTree(data));
		if (datadiff.size() != 0) {
			System.out.println(name + " changed:");
			System.out.println(datadiff);
			System.out.println("Original data at " +
					getSavedDataFilePath(tspec, fs, ext));
		}
		return datadiff;
	}

	private void writeObjectAsJsonToFile(Object data,
			TestSpec tspec, FileSpec fs, String extension) throws Exception {
		Path p = getSavedDataFilePath(tspec, fs, extension);
		BufferedWriter writer = Files.newBufferedWriter(p,
				Charset.forName("UTF-8"));

		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
		
		mapper.writeValue(writer, data);
	}
	
	private Map<String, Object> loadWorkspaceObject(TestSpec tspec,
			FileSpec fs) throws Exception {
		BufferedReader reader = getReaderForFile(tspec, fs, EXT_JSON);
		@SuppressWarnings("unchecked")
		Map<String, Object> data = new ObjectMapper()
				.readValue(reader, Map.class);
		reader.close();
		return data;
	}

	private Map<String, String> loadWorkspaceObjectMeta(TestSpec tspec,
			FileSpec fs) throws Exception {
		BufferedReader reader = getReaderForFile(tspec, fs,
				EXT_META_JSON);
		@SuppressWarnings("unchecked")
		Map<String, String> meta = new ObjectMapper()
				.readValue(reader, Map.class);
		reader.close();
		return meta;
	}
	
	private List<ProvenanceAction> loadWorkspaceObjectProvenance(
			TestSpec tspec, FileSpec fs) throws Exception {
		BufferedReader reader = getReaderForFile(tspec, fs,
				EXT_PROV_JSON);
		@SuppressWarnings("unchecked")
		List<ProvenanceAction> prov = new ObjectMapper().readValue(reader,
				List.class);
		reader.close();
		return prov;
	}

	private BufferedReader getReaderForFile(TestSpec tspec,
			FileSpec fs, String extension) throws Exception {
		Path p = getSavedDataFilePath(tspec, fs, extension);
		BufferedReader reader = Files.newBufferedReader(p,
				Charset.forName("UTF-8"));
		return reader;
	}
	
	private Path getSavedDataFilePath(TestSpec tspec, FileSpec fs,
			String extension) {
		String filesep = "%-%";
		String filename = tspec.getOrganismCode() + filesep +
				fs.getLocation().getGroup().replace(' ', '_') + filesep +
				fs.getLocation().getFile() + extension;
		return Paths.get(WS_OBJECTS_FOLDER, filename).toAbsolutePath();
	}

	private String getTestMethodName() {
		Exception e = new Exception();
		e.fillInStackTrace();
		for (int i = 1; i < 4; i++) {
			if (!e.getStackTrace()[i].getMethodName().equals("runTest")) {
				return e.getStackTrace()[i].getMethodName();
			}
		}
		throw new TestException("Couldn't get test method name");
	}

	private static void checkTimeout(Long startNanos, int timeoutSec,
			String message) {
		if ((System.nanoTime() - startNanos) / 1000000000 > timeoutSec) {
			System.out.println(message);
			throw new TestException(message);
		}
	}
	
	public static String calculateElapsed(Date start, Date complete) {
		double secdiff = ((double) (complete.getTime() - start.getTime()))
				/ 1000.0;
		long hours = (long) secdiff / 3600;
		long mins = (long) secdiff / 60;
		double secs = secdiff % 60;
		return hours + "h " + mins + "m " + String.format("%.3fs", secs);
	}
}
