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
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.abstracthandle.AbstractHandleClient;
import us.kbase.abstracthandle.Handle;
import us.kbase.auth.AuthService;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.ServerException;
import us.kbase.jgiintegration.common.JGIFileLocation;
import us.kbase.jgiintegration.common.JGIOrganismPage;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.ShockFileInformation;
import us.kbase.shock.client.ShockNode;
import us.kbase.shock.client.ShockNodeId;
import us.kbase.wipedev03.WipeDev03Client;
import us.kbase.workspace.CreateWorkspaceParams;
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


/** Runs JGI / KBase integration tests for the Push to KBase (PtKB)
 * functionality.
 * 
 * The general procedure is:
 * 
 * 1. Create a TestSpec
 * 2. Create a web client (e.g. the HtmlUnit equivalent of a browser)
 * 3. Select the file(s) to push via the JGI web UI as per the test spec.
 * 4. Clear the Gmail inbox.
 * 5. Push the file(s).
 * 6. For each file:
 *     1. Wait for the workspace object to push to the workspace
 *     2. Check that the data is correct:
 *         1. workspace metadata
 *         2. workspace object data (stored in the folder set below)
 *         3. workspace object metadata (stored in the folder set below)
 *         4. workspace object provenance (stored in the folder set below)
 *         5. workspace object type
 *         6. workspace object version
 *         7. handle information
 *         8. shock MD5 and filename
 *     3. Check that the success email sent from JGI is correct and delete
 * 7. Close the web client.
 * 
 * Most tests only require the programmer to perform step 1 and call runTest()
 * but see below for more complicated tests.
 * 
 * @author gaprice@lbl.gov
 *
 */
public class JGIIntegrationTest {
	
	//should probably use slf4j instead of print statements, but can't be arsed for now
	
	/* Set to true to write retrieved workspace objects to the objects folder
	 * set below. This will overwrite the prior objects, so archive them if
	 * necessary first.
	 */
	private static final boolean SAVE_WS_OBJECTS = false;
	
	/* The folder where workspace objects used in the tests are kept. */
	private static final String WS_OBJECTS_FOLDER = "workspace_objects";
	
	/* Print more stuff when checking email. */
	private static final boolean DEBUG_EMAIL = false;
	
	/* The url of the test workspace service. */
	private static final String WS_URL =
			"https://dev03.berkeley.kbase.us/services/ws";
	
	/* The url of the test handle service. */
	private static final String HANDLE_URL = 
			"https://dev03.berkeley.kbase.us/services/handle_service";
	
	/* The url of the wipe server. */
	private static final String WIPE_URL = 
			"http://dev03.berkeley.kbase.us:9000";

	/* How long should a test wait for a push before it fails? */
	private static final int PUSH_TO_WS_TIMEOUT_SEC = 30 * 60; //30min
	
	/* How often the tests check the workspace for a completed push. */
	private static final int PUSH_TO_WS_SLEEP_SEC = 5;
	
	/* Don't wipe the databases on the test server before running tests. */
	private static final boolean SKIP_WIPE = false;
	
	/* Don't check that workspace object versions match the expected version.
	 * If the server isn't wiped, this should probably be true.
	 */
	private static final boolean SKIP_VERSION_ASSERT = false;
	
	/* Extensions for workspace object, workspace meta, and workspace
	 * provenance saved files. Files are saved in WS_OBJECTS_FOLDER.
	 */
	private static final String EXT_JSON = ".json";
	private static final String EXT_META_JSON = ".meta.json";
	private static final String EXT_PROV_JSON = ".prov.json";
	
	/* The workspace type for JGI reads. */
	private static final String TYPE_READ_PREFIX =
			"KBaseFile.PairedEndLibrary";
	
	/* The workspace type for JGI assemblies. */
	private static final String TYPE_ASSEMBLY_PREFIX =
			"KBaseFile.AssemblyFile";
	
	/* The workspace type for JGI annotations. */
	private static final String TYPE_ANNOTATION_PREFIX =
			"KBaseFile.AnnotationFile";
	
	/* The location of the shock file information inside, respectively, read,
	 * assembly, and annotation workspace objects.
	 */
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
	
	/* The expected subject for an email sent upon a PtKB success. */
	private static String MAIL_SUBJECT_SUCCESS =
			"JGI/KBase data transfer succeeded";
	
	/* The expected beginning for an email sent upon a PtKB success. */
	private static List<String> MAIL_BODY_SUCCESS_START =
			new LinkedList<String>();
	static {
		MAIL_BODY_SUCCESS_START.add("Dear KBase user,");
		MAIL_BODY_SUCCESS_START.add("Your data has been pushed to KBase.");
		MAIL_BODY_SUCCESS_START.add("You can find your imported files at the links below.");
		MAIL_BODY_SUCCESS_START.add("");
	}
	
	/* The expected end for an email sent upon a PtKB success. */
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
	
	/* The expected subject for an email sent upon a PtKB failure. */
	private static String MAIL_SUBJECT_FAIL = "JGI/KBase data transfer failed";
	
	/* The expected body for an email sent upon a PtKB failure. */
	private static String MAIL_BODY_FAIL = 
			"\r\nDear KBase user, \r\n" +
			"\r\n" +
			"An unexpected error occurred while processing your upload request for %s.\r\n" +
			"\r\n" +
			"An email has been sent to the system administrators. If this is urgent, please contact help@kbase.us\r\n" +
			"\r\n" +
			"JGI-KBase";
	
	/* The username of the JGI account to use in testing. */
	private static String JGI_USER;
	/* The password of the JGI account to use in testing. */
	private static String JGI_PWD;
	/* The username of the first KBase account to use in testing. */
	private static String KB_USER_1;
	/* The password of the first KBase account to use in testing. */
	private static String KB_PWD_1;
	/* The username of the second KBase account to use in testing. */
	private static String KB_USER_2;
	/* The password of the second KBase account to use in testing. */
	private static String KB_PWD_2;
	/* The username of the shock administrator account to use in testing. */
	private static String KB_SHOCKADMIN_USER;
	/* The password of the shock administrator account to use in testing. */
	private static String KB_SHOCKADMIN_PWD;
	
	/* The Gmail folder where test emails will be recieved. */
	private static Folder GMAIL;
	
	/* The Handle Service client. */
	private static AbstractHandleClient HANDLE_CLI;
	
	/* The Wipe service client. */
	@SuppressWarnings("unused")
	private static WipeDev03Client WIPE;
	
	/* Converts from Object <-> JSON. */
	private static final ObjectMapper SORTED_MAPPER = new ObjectMapper();
	static {
		SORTED_MAPPER.configure(
				SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	}
	
	/* Get the configuration info from the configuration file,
	 * wipe the server, and set up the Gmail folder and Handle Service client. 
	 */
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
		KB_SHOCKADMIN_USER = System.getProperty("test.kbase.shockadmin.user");
		KB_SHOCKADMIN_PWD = System.getProperty("test.kbase.shockadmin.pwd");
		String gmailuser = System.getProperty("test.kbase.jgi.gmail.user");
		String gmailpwd = System.getProperty("test.kbase.jgi.gmail.pwd");
		
		String wipeUser = System.getProperty("test.kbase.wipe_user");
		String wipePwd = System.getProperty("test.kbase.wipe_pwd");
		if (!SKIP_WIPE) {
			WIPE = wipeRemoteServer(new URL(WIPE_URL), wipeUser, wipePwd);
		}

		System.out.print("Connecting to gmail test account... ");
		try {
			Session session = Session.getInstance(new Properties());
			Store store = session.getStore("imaps");
			store.connect("imap.gmail.com", gmailuser, gmailpwd);
			GMAIL = store.getFolder("inbox");
		} catch (MessagingException me) {
			System.out.println("Connecting to gmail failed.");
			throw me;
		}
		System.out.println("Done.");
		
		HANDLE_CLI = new AbstractHandleClient(
				new URL(HANDLE_URL), KB_USER_1, KB_PWD_1);
		HANDLE_CLI.setIsInsecureHttpConnectionAllowed(true);
		HANDLE_CLI.setAllSSLCertificatesTrusted(true);
	}
	
	/** A specification for a JGI file pushable from an organism page.
	 * @author gaprice@lbl.gov
	 *
	 */
	private static class FileSpec {
		private final JGIFileLocation location;
		private final String type;
		private final long expectedVersion;
		private final String shockMD5;

		/** Construct a JGI file spec.
		 * @param location the location of the file.
		 * @param workspaceType the expected workspace type of the workspace
		 * object that will be pushed for this file.
		 * @param expectedVersion the expected version of the workspace object
		 * that will be pushed for this file.
		 * @param shockMD5 the expected MD5 of the Shock node for this file.
		 */
		public FileSpec(JGIFileLocation location, String workspaceType,
				long expectedVersion, String shockMD5) {
			super();
			this.location = location;
			this.type = workspaceType;
			this.expectedVersion = expectedVersion;
			this.shockMD5 = shockMD5;
		}

		/** Get the file's location.
		 * @return the file's location.
		 */
		public JGIFileLocation getLocation() {
			return location;
		}

		/** Get the file's expected workspace type.
		 * @return the file's expected workspace type.
		 */
		public String getWorkspaceType() {
			return type;
		}

		/** Get the file's expected version in the workspace.
		 * @return the file's expected version in the workspace.
		 */
		public long getExpectedVersion() {
			return expectedVersion;
		}

		/** Get the file's expected MD5 as provided by Shock.
		 * @return the file's expected MD5 as provided by Shock.
		 */
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
	
	/** A specification for a PtKB test.
	 * @author gaprice@lbl.gov
	 *
	 */
	private static class TestSpec {
		private final String organismCode;
		private final String kbaseUser;
		private final String kbasePassword;
		private final List<FileSpec> filespecs =
				new LinkedList<JGIIntegrationTest.FileSpec>();
		private final List<FileSpec> unselect =
				new LinkedList<JGIIntegrationTest.FileSpec>();
		
		/** Construct a new test specification.
		 * @param organismCode the JGI code for the organism page to be tested.
		 * @param kbaseUser the username of the user that will push files.
		 * @param kbasePassword the password of the user that will push files.
		 */
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
		
		/** Add a file specification to the test.
		 * @param spec a file specification.
		 */
		public void addFileSpec(FileSpec spec) {
			addFileSpec(spec, false);
		}
		
		/** Add a file specification to the test.
		 * @param spec a file specification
		 * @param unselect true to unselect the file after selecting via the
		 * web UI. The file should then not be pushed.
		 */
		public void addFileSpec(FileSpec spec, boolean unselect) {
			filespecs.add(spec);
			if (unselect) {
				this.unselect.add(spec);
			}
		}
		
		/** Get the organism code for this test.
		 * @return the organism code for this test.
		 */
		public String getOrganismCode() {
			return organismCode;
		}
		
		/** Get the KBase username.
		 * @return the KBase username.
		 */
		public String getKBaseUser() {
			return kbaseUser;
		}
		
		/** Get the KBase password.
		 * @return the KBase password.
		 */
		public String getKBasePassword() {
			return kbasePassword;
		}

		/** Get all file specs associated with this test.
		 * @return all file specs associated with this test.
		 */
		public List<FileSpec> getFilespecs() {
			return new LinkedList<FileSpec>(filespecs);
		}
		
		/** Get all filespecs to be unselected after being selected.
		 * @return all filespecs to be unselected after being selectd.
		 */
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
	
	/** The result of pushing one file to KBase from JGI.
	 * @author gaprice@lbl.gov
	 *
	 */
	private static class TestResult {
		private final String shockID;
		private final String shockURL;
		private final String handleID;
		private final String workspaceName;
		
		/** Create a new test result.
		 * @param workspaceName the name of the workspace where the data was
		 * pushed.
		 * @param shockID the shock node ID for the data.
		 * @param shockURL the shock server url where the data is stored.
		 * @param handleID the handle ID for the data.
		 */
		public TestResult(String workspaceName, String shockID,
				String shockURL, String handleID) {
			super();
			this.shockID = shockID;
			this.shockURL = shockURL;
			this.handleID = handleID;
			this.workspaceName = workspaceName;
		}

		/** Get the data's shock ID.
		 * @return the data's shock ID.
		 */
		public String getShockID() {
			return shockID;
		}

		/** Get the URL of the shock service where the data is stored.
		 * @return the URL of the shock service where the data is stored.
		 */
		public String getShockURL() {
			return shockURL;
		}

		/** Get the handle ID for the data.
		 * @return the handle ID for the data.
		 */
		public String getHandleID() {
			return handleID;
		}
		
		/** Get the name of the workspace where the data was stored.
		 * @return the name of the workspace where the data was stored.
		 */
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
	
	/** A test exception.
	 * @author gaprice@lbl.gov
	 *
	 */
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
	
	/** Create the target workspace so JGI can't write to it, push a file, wait
	 * for the push to fail and JGI to send the failure email, check the email
	 * is as expected. Note that a failure email will be sent to the jgi /
	 * kbase email list.
	 * @throws Exception if an exception occurs.
	 */
	@Test 
	public void pushFailedEmail() throws Exception {
		int emailTimeoutSec = 30 * 60;
		
		// create the workspace before JGI can, which should cause an error
		String workspaceName =
				"Borrelia_coriaceae_ATCC_43381_kbasetest";
		WorkspaceClient wsClient = new WorkspaceClient(new URL(WS_URL),
				KB_USER_2, KB_PWD_2);
		wsClient.setIsInsecureHttpConnectionAllowed(true);
		wsClient.setAllSSLCertificatesTrusted(true);
		wsClient.createWorkspace(new CreateWorkspaceParams()
				.withWorkspace(workspaceName));
		
		Date start = new Date();
		TestSpec tspec = new TestSpec("BorcorATCC43381_FD", KB_USER_1,
				KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"7162.2.63086.CACTCA.adnq.fastq.gz"),
				"foo", 1L,
				"foo"));
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

		cli.close();

		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
	}

	/** Push a single reads file.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void pushSingleFile() throws Exception {
		TestSpec tspec = new TestSpec("BlaspURHD0036_FD", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"7625.2.79179.AGTTCC.adnq.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"5c66abbb2515674a074d2a41ecf01017"));
		runTest(tspec);
	}
	
	/** Push a single file, delete the shock node for that file, and push
	 * again to ensure that JGI creates a new node even thought the old node
	 * is memoized JGI side.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void pushSingleFileDeleteShockNodeAndRepush() throws Exception {
		/* Tests JGI code that memoizes shocknodes for files that have been
		 * previously pushed.
		 */
		TestSpec tspec = new TestSpec(
				"MarpieDSM16108_FD", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"9364.7.131005.CTAGCT.anqdp.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"6bd062af06cf31f73eea9906bbe6ae85"));
		TestResult tr = runTest(tspec).get(tspec.getFilespecs().get(0));
		
		AuthToken token = AuthService.login(KB_SHOCKADMIN_USER,
				KB_SHOCKADMIN_PWD).getToken();
		BasicShockClient cli = new BasicShockClient(new URL(tr.getShockURL()),
				token);
		cli.getNode(new ShockNodeId(tr.getShockID())).delete();
		
		TestSpec tspec2 = new TestSpec(
				"MarpieDSM16108_FD", KB_USER_1, KB_PWD_1);
		tspec2.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"9364.7.131005.CTAGCT.anqdp.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 2L,
						"6bd062af06cf31f73eea9906bbe6ae85"));
		TestResult tr2 = runTest(tspec2).get(tspec2.getFilespecs().get(0));
		assertThat("Used different shock node",
				tr.getShockID().equals(tr2.getShockID()), is(false));
		assertThat("Used different handle id",
				tr.getHandleID().equals(tr2.getHandleID()), is(false));
	}
	
	/** Push an assembly
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void pushAssembly() throws Exception {
		TestSpec tspec = new TestSpec(
				"Altbac1120WS0a04_FD", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC and Genome Assembly",
						"final.assembly.fasta"),
						"KBaseFile.AssemblyFile-2.1", 1L,
						"d8dcd51d2fdb26609616de834293860e"));
		runTest(tspec);
	}
	
	/** Attempt to push an annotation but have it rejected by the JGI front
	 * end.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void rejectAnnotation() throws Exception {
		TestSpec tspec = new TestSpec(
				"BraalvATCC51933_FD", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("IMG Data",
						"21614.assembled.gff",
						true), //expect rejection
						"foo", 1L,
						"foo"));
		runTest(tspec);
	}
	
	//restore when we push annotations again
//	@Test
//	public void pushAnnotation() throws Exception {
//		TestSpec tspec = new TestSpec(
//				"ThaarcSCAB663P07_FD", KB_USER_1, KB_PWD_1);
//		tspec.addFileSpec(new FileSpec(
//				new JGIFileLocation("IMG Data",
//						"14052.assembled.gff"),
//						"KBaseFile.AnnotationFile-2.1", 1L,
//						"04c5df5bb396cb80842befb9ff47e35b"));
//		runTest(tspec);
//	}
	
	/** Push two files in different groups simultaneously.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void pushTwoFiles() throws Exception {
		TestSpec tspec = new TestSpec(
				"AlimarDSM23064_FD", KB_USER_1, KB_PWD_1);
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
	
	/** Push two files in the same group simultaneously.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void pushTwoFilesSameGroup() throws Exception {
		TestSpec tspec = new TestSpec(
				"AmybenAK1665_FD", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("Raw Data",
						"2152.6.1795.ACAGTG.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"08430ed1f45efee29d50ababab7c2985"));
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("Raw Data",
						"2168.6.1789.TGACCA.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"788db30833c718bd8a7868a2670dffc4"));
		runTest(tspec);
		
	}
	
	/** Push the same file twice using the same HttpUnit client (e.g. the
	 * same browser session). Both push results should have the same shock node.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void pushSameFileWithSameClient() throws Exception {
		FileSpec fs1 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"8337.2.99299.CTCAGA.anqdp.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"f0d3265529a02aa67bcc6a93c7264681");
		
		FileSpec fs2 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"8337.2.99299.CTCAGA.anqdp.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 2L,
				"f0d3265529a02aa67bcc6a93c7264681");
		
		TestSpec tspec1 = new TestSpec(
				"BreandATCC43811_FD", KB_USER_1, KB_PWD_1);
		tspec1.addFileSpec(fs1);
		
		TestSpec tspec2 = new TestSpec(
				"BreandATCC43811_FD", KB_USER_1, KB_PWD_1);
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
		
		cli.close();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		assertThat("Pushing same file twice uses same shock node",
				res2.get(fs2), is(res1.get(fs1)));
	}
	
	/** Push the same file twice using the a new HttpUnit client each time
	 * (e.g. the browser was closed between pushes). Both push results should
	 * have the same shock node.
	 * @throws Exception if an exception occurs.
	 */
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
		
		TestSpec tspec1 = new TestSpec(
				"ActgenspDSM45722_FD", KB_USER_1, KB_PWD_1);
		tspec1.addFileSpec(fs1);
		
		TestSpec tspec2 = new TestSpec(
				"ActgenspDSM45722_FD", KB_USER_1, KB_PWD_1);
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
		
		cli.close();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		assertThat("Pushing same file twice uses same shock node",
				res2.get(fs2), is(res1.get(fs1)));
	}
	
	/** Push the same file twice using different KBase user credentials. Both
	 * push results should have the same shock node.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void pushSameFileDifferentUsers() throws Exception {
		FileSpec fs1 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6386.5.43682.CTTGTA.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"38a326d7a24440060932954f46fd4fd5");
		
		FileSpec fs2 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6386.5.43682.CTTGTA.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"38a326d7a24440060932954f46fd4fd5");
		
		TestSpec tspec1 = new TestSpec("AchaxaATCC25176_FD", KB_USER_1,
				KB_PWD_1);
		tspec1.addFileSpec(fs1);
		
		TestSpec tspec2 = new TestSpec("AchaxaATCC25176_FD", KB_USER_2,
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
		
		cli.close();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is(true));
		assertThat("Pushing same file twice uses same shock node",
				res2.get(fs2).getShockID(), is(res1.get(fs1).getShockID()));
		assertThat("Pushing same file twice uses same shock url",
				res2.get(fs2).getShockURL(), is(res1.get(fs1).getShockURL()));
		assertThat("Pushing same file twice uses same handle id",
				res2.get(fs2).getHandleID(), is(res1.get(fs1).getHandleID()));
	}
	
	/** Select and push nothing. This exercises the JGI front end only.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void pushNothing() throws Exception {
		TestSpec tspec = new TestSpec(
				"BlaspURHD0036_FD", KB_USER_1, KB_PWD_1); //if parallelize, change to unused page
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
	
	/** Select a file, unselect it, and thus push nothing.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void unselectAndPushNothing() throws Exception {
		TestSpec tspec = new TestSpec(
				"BlaspURHD0036_FD", KB_USER_1, KB_PWD_1); //if parallelize, change to unused page
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
	
	/** Select two files, unselect one, and push.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void unselectAndPushOne() throws Exception {
		TestSpec tspec = new TestSpec(
				"GeobraDSM44526_FD", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"8446.4.101451.ACGATA.anqdp.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo"),
				true); //unselect after selecting
		FileSpec spec = new FileSpec(new JGIFileLocation("Raw Data",
						"8446.4.101451.ACGATA.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"04cc65af00b0b0cd0afc91b002798fb1");
		tspec.addFileSpec(spec);
		String wsName = runTest(tspec).get(spec).getWorkspaceName();
		
		WorkspaceClient wsCli = new WorkspaceClient(
				new URL(WS_URL), KB_USER_1, KB_PWD_1);
		wsCli.setIsInsecureHttpConnectionAllowed(true);
		wsCli.setAllSSLCertificatesTrusted(true);
		assertThat("Only one object in workspace",
				wsCli.listObjects(new ListObjectsParams()
						.withWorkspaces(Arrays.asList(wsName))).size(), is(1));
	}
	
	/** Select a file that should be rejected and try and push it. 
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void rejectOneFile() throws Exception {
		TestSpec tspec = new TestSpec(
				"AciangATCC35903_FD", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC and Genome Assembly",
						"QC.finalReport.pdf",
						true), //expect rejection
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo1")
				);
		runTest(tspec);
	}
	
	/** Select two files, one of which should be rejected, and push them.
	 * @throws Exception if an exception occurs.
	 */
	@Test
	public void rejectOnePushOne() throws Exception {
		TestSpec tspec = new TestSpec(
				"BacbogATCCBAA922_FD", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC and Genome Assembly",
						"7505.3.75449.ACAGTG.artifact.clean.fastq.gz",
						true), //expect rejection
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo")
				);
		FileSpec spec = new FileSpec(new JGIFileLocation(
				"QC Filtered Raw Data",
				"7505.3.75449.ACAGTG.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"899dd799090c16e4efb14660e517cfb5");
		tspec.addFileSpec(spec);
		
		String wsName = runTest(tspec).get(spec).getWorkspaceName();
		
		WorkspaceClient wsCli = new WorkspaceClient(
				new URL(WS_URL), KB_USER_1, KB_PWD_1);
		wsCli.setIsInsecureHttpConnectionAllowed(true);
		wsCli.setAllSSLCertificatesTrusted(true);
		assertThat("Only one object in workspace",
				wsCli.listObjects(new ListObjectsParams()
						.withWorkspaces(Arrays.asList(wsName))).size(), is(1));
	}
	
	/** Run a test - push files to KBase and check the results. Asserts that
	 * no web page alerts occured.
	 * @param tspec the specification for the test
	 * @return a mapping of file specification to the result for that file.
	 * @throws Exception if an exception occurs.
	 */
	private Map<FileSpec, TestResult> runTest(TestSpec tspec)
			throws Exception {
		List<String> alerts = new LinkedList<String>();
		Map<FileSpec, TestResult> res =
				runTest(tspec, new CollectingAlertHandler(alerts));
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		return res;
	}

	/** Run a test - push files to KBase and check the results.
	 * @param tspec the specification for the test
	 * @param handler a handler for web page alerts.
	 * @return a mapping of file specification to the result for that file.
	 * @throws Exception if an exception occurs.
	 */
	private Map<FileSpec, TestResult> runTest(
			TestSpec tspec, AlertHandler handler)
			throws Exception {
		WebClient cli = new WebClient();
		Date start = new Date();
		System.out.println("Starting test " + getTestMethodName());
		String wsName = processTestSpec(tspec, cli, handler, false);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s",
				new Date(), getTestMethodName()));
		
		Map<FileSpec, TestResult> res = checkResults(tspec, wsName);
		cli.close();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		return res;
	}

	/** Process a test specification - select files and push them to KBase.
	 * Also clears the Gmail inbox.
	 * @param tspec the test specification to process.
	 * @param cli the web client with which to connect to JGI.
	 * @param handler a handler for web page alerts.
	 * @param skipLogin true to skip logging into JGI.
	 * @return the workspace name created as a result of the push.
	 * @throws Exception if an exception occurs.
	 */
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
		
		System.out.print("Clearing test email account... ");
		if (!GMAIL.isOpen()) {
			GMAIL.open(Folder.READ_WRITE);
		}
		for (Message m: GMAIL.getMessages()) {
			m.setFlag(Flags.Flag.DELETED, true);
		}
		GMAIL.expunge();
		System.out.println("Done.");
		
		org.pushToKBase(tspec.getKBaseUser(), tspec.getKBasePassword());
		return org.getWorkspaceName(tspec.getKBaseUser());
	}

	/** Check the results of a test - e.g. correct workspace, handle, and
	 * shock data, correct emails. Deletes emails after checking.
	 * @param tspec the test specification to check.
	 * @param workspace the name of the workspace associated with the test
	 * spec.
	 * @return a mapping of file specification to the result for that file.
	 * @throws Exception if an exception occurs.
	 */
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
						checkErrorAcceptable(fs, workspace, se);
					} //otherwise try again
					Thread.sleep(PUSH_TO_WS_SLEEP_SEC * 1000);
				}
			}
			System.out.println(String.format(
					"Retrieved file from workspace after %s seconds",
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

	/** Check that only legal files pushed to KBase.
	 * @param tspec the test specification to check.
	 * @param workspace the name of the workspace associated with the test
	 * specification.
	 * @param wsClient the workspace client to use to communicate with the workspace.
	 * @throws Exception if an exception occurs.
	 */
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
					checkErrorAcceptable(fs, workspace, se);
				}
			}
		}
	}

	/** If the workspace throws an exception when checking for file existence,
	 * check if the error simply means the file has not yet been pushed and
	 * still may be pushed in the future.
	 * @param fs the file specification to check.
	 * @param workspace the workspace where the file is located.
	 * @param se the workspace exception.
	 */
	private void checkErrorAcceptable(FileSpec fs, String workspace,
			ServerException se) {
		// for just do this one off, should add regex matchers in a list if
		// this function becomes too unwieldy
		String e1 = String.format(
				"Object %s cannot be accessed: No workspace with name %s exists",
				fs.getLocation().getFile(), workspace);
		
		String e2 = String.format(
				"No object with name %s exists in workspace",
				fs.getLocation().getFile());
		
		// this exception should rarely happen, occurs when version in mongo
		// workspaceObject document has been incremented but the version
		// document hasn't been saved
		String e3start = "No object with id";
		String e3end = String.format(
				"(name %s) and version %s exists in workspace",
				fs.getLocation().getFile(), fs.getExpectedVersion());
				
		if (se.getMessage().equals(e1)) {
			return; //ok
		}
		if (se.getMessage().startsWith(e2)) {
			return; //ok
		}
		if (se.getMessage().startsWith(e3start) &&
				se.getMessage().contains(e3end)) {
			return; //ok;
		}
		System.out.println(String.format(
				"Got unnacceptable exception at %s:", new Date()));
		System.out.println(se);
		System.out.println(se.getData());
		fail("got unacceptable exception from workspace: " + se);
	}

	/** Check the data pushed to KBase against expected data for a test.
	 * @param wsObj the object data retrieved from the workspace
	 * @param tspec the test specification to check against.
	 * @param fs the file specification associated with the workspace data.
	 * @return the results of the test.
	 * @throws Exception if an exception occurs.
	 */
	private TestResult checkResults(
			ObjectData wsObj, TestSpec tspec, FileSpec fs)
			throws Exception {
		String fileContainerName = getFileContainerName(fs.getWorkspaceType());
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
				is(fs.getWorkspaceType()));
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
	
	/** Check that the email sent as a result of a push is correct.
	 * @param ws the workspace name associated with the test.
	 * @param tspec the test specification to check.
	 * @throws Exception if an exception occurs.
	 */
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

	private void debugEmail(String msg) {
		if (DEBUG_EMAIL) {
			System.out.println(msg);
		}
	}
	
	/** Get the body of a PtKB success or failure email.
	 * @param timeoutSec the maximum time to wait for the email in seconds.
	 * @param success true for a success email, false otherwise.
	 * @return the body of the email.
	 * @throws MessagingException if a messaging exception occurs.
	 * @throws IOException if an IO exception occurs.
	 * @throws InterruptedException if the thread is interrupted while sleeping.
	 */
	private String getPtKBEmailBody(int timeoutSec, boolean success)
			throws MessagingException, IOException, InterruptedException {
		String subject = success ? MAIL_SUBJECT_SUCCESS : MAIL_SUBJECT_FAIL;
		String body = null;
		Long start = System.nanoTime();
		
		System.out.print("Getting email... ");
		while(body == null) {
			debugEmail("");
			checkTimeout(start, timeoutSec,
					String.format(
					"Timed out attempting to retrieve push " + 
					(success ? "success" : "fail") + " email after %s sec",
					timeoutSec));
			
			debugEmail("Opening gmail");
			if (!GMAIL.isOpen()) {
				GMAIL.open(Folder.READ_WRITE);
			}
			
			debugEmail("Getting messages");
			for (Message m: GMAIL.getMessages()) {
				debugEmail("Subject: " + m.getSubject());
				if (body == null) {
					if (m.getSubject().equals(subject)) {
						MimeMultipart mm = (MimeMultipart) m.getContent();
						body = mm.getBodyPart(0).getContent().toString();
					}
				}
				debugEmail("Deleting message");
				m.setFlag(Flags.Flag.DELETED, true); //clear the inbox after each test
			}
			debugEmail("Expunging");
			GMAIL.expunge();
			debugEmail("Sleeping");
			Thread.sleep(PUSH_TO_WS_SLEEP_SEC * 1000);
		}
		System.out.println(String.format(
				"retrieved " + (success ? "success" : "fail") +
				" email after %s seconds",
				((System.nanoTime() - start) / 1000000000)));
		return body;
	}

	/** Check that a success email body matches the expected body.
	 * @param ws the workspace name associate with the test that produced
	 * the email.
	 * @param tspec the test specification that resulted in the email.
	 * @param body the body of the received email.
	 */
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

	/** Given a workspace type (even a prefix), provide the path in the
	 * workspace object where the handle information is stored.
	 * @param type
	 * @return
	 */
	private String getFileContainerName(String type) {
		for (String typePrefix: TYPE_TO_FILELOC.keySet()) {
			if (type.startsWith(typePrefix)) {
				return TYPE_TO_FILELOC.get(typePrefix);
			}
		}
		throw new TestException("Unsupported type: " + type);
	}

	/** Check that the workspace data from a push matches the expected data
	 * stored in WS_OBJECTS_FOLDER.
	 * @param tspec the test specification to check against.
	 * @param fs the file specification for the object to check.
	 * @param wsdata the object data from the workspace.
	 * @param wsmeta the object metadata from the workspace.
	 * @param prov the object provenance from the workspace.
	 * @return a JSON diff of the data, metadata, and provenance in a list.
	 * @throws Exception if an exception occurs.
	 */
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

	/** Check that two data structures are equivalent; print info to stdout
	 * if they're not.
	 * @param expectedData the expected data structure.
	 * @param data the actual data structure.
	 * @param tspec the test specification that resulted in the data
	 * @param fs the file specification for this data
	 * @param name the name of the data
	 * @param ext the file extension of the expected data file 
	 * @return
	 */
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

	/** Write a data structure to a file in JSON format.
	 * @param data the data to write.
	 * @param tspec the test specification that resulted in the data.
	 * @param fs the file specification for this data.
	 * @param extension the file extension for the file to be written.
	 * @throws Exception if an exception occurs.
	 */
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
	
	/** Load a workspace object from file.
	 * @param tspec the test specification associated with the object.
	 * @param fs the file specification for the object.
	 * @return the object.
	 * @throws Exception if an exception occurs.
	 */
	private Map<String, Object> loadWorkspaceObject(TestSpec tspec,
			FileSpec fs) throws Exception {
		BufferedReader reader = getReaderForFile(tspec, fs, EXT_JSON);
		@SuppressWarnings("unchecked")
		Map<String, Object> data = new ObjectMapper()
				.readValue(reader, Map.class);
		reader.close();
		return data;
	}

	/** Load workspace object metadata from file.
	 * @param tspec the test specification associated with the metadata.
	 * @param fs the file specification for the metadata.
	 * @return the metadata.
	 * @throws Exception if an exception occurs.
	 */
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
	
	/** Load workspace object provenance from file.
	 * @param tspec the test specification associated with the provenance.
	 * @param fs the file specification for the provenance.
	 * @return the provenance.
	 * @throws Exception if an exception occurs.
	 */
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

	/** Get a reader for a saved workspace data file.
	 * @param tspec the test specification associated with the file.
	 * @param fs the file specification associated with the file.
	 * @param extension the file extension.
	 * @return a reader.
	 * @throws Exception if an exception occurs.
	 */
	private BufferedReader getReaderForFile(TestSpec tspec,
			FileSpec fs, String extension) throws Exception {
		Path p = getSavedDataFilePath(tspec, fs, extension);
		BufferedReader reader = Files.newBufferedReader(p,
				Charset.forName("UTF-8"));
		return reader;
	}
	
	/** Get the file path for a saved workspace data file.
	 * @param tspec the test specification associated with the file.
	 * @param fs the file specification associated with the file.
	 * @param extension the file extension.
	 * @return the file path.
	 */
	private Path getSavedDataFilePath(TestSpec tspec, FileSpec fs,
			String extension) {
		String filesep = "%-%";
		String filename = tspec.getOrganismCode() + filesep +
				fs.getLocation().getGroup().replace(' ', '_') + filesep +
				fs.getLocation().getFile() + extension;
		return Paths.get(WS_OBJECTS_FOLDER, filename).toAbsolutePath();
	}

	/** Get the method name for a running test. Should be called in the
	 * test body or automatically in runTest().
	 * @return the name of the runnnig test method.
	 */
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

	/** Check if it's GAME OVER, MAN
	 * @param startNanos when the timer started in nanoseconds.
	 * @param timeoutSec the timeout time in seconds
	 * @param message the message to print to stdout and throw in an exception
	 * if the timeout occurs.
	 */
	private static void checkTimeout(Long startNanos, int timeoutSec,
			String message) {
		if ((System.nanoTime() - startNanos) / 1000000000 > timeoutSec) {
			System.out.println(message);
			throw new TestException(message);
		}
	}
	
	/** Calculate the time elapsed between two dates and format as a string,
	 * e.g. 4h 3m 60.123s
	 * @param start the start date.
	 * @param complete the end date.
	 * @return the time elapsed as a formatted string.
	 */
	public static String calculateElapsed(Date start, Date complete) {
		double secdiff = ((double) (complete.getTime() - start.getTime()))
				/ 1000.0;
		long hours = (long) secdiff / 3600;
		long mins = (long) secdiff / 60;
		double secs = secdiff % 60;
		return hours + "h " + mins + "m " + String.format("%.3fs", secs);
	}
}
