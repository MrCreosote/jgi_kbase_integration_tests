package us.kbase.jgiintegration.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import us.kbase.abstracthandle.AbstractHandleClient;
import us.kbase.abstracthandle.Handle;
import us.kbase.auth.AuthService;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.ServerException;
import us.kbase.common.service.Tuple2;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.ShockNode;
import us.kbase.shock.client.ShockNodeId;
import us.kbase.wipedev03.WipeDev03Client;
import us.kbase.workspace.ListObjectsParams;
import us.kbase.workspace.ObjectData;
import us.kbase.workspace.ObjectIdentity;
import us.kbase.workspace.WorkspaceClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gargoylesoftware.htmlunit.AlertHandler;
import com.gargoylesoftware.htmlunit.CollectingAlertHandler;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.DomNode;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlCheckBoxInput;
import com.gargoylesoftware.htmlunit.html.HtmlDivision;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
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
	
	//for testing
	private static final boolean SKIP_WIPE = false;
	private static final boolean SKIP_VERSION_ASSERT = false;
	
	private static String JGI_USER;
	private static String JGI_PWD;
	private static String KB_USER_1;
	private static String KB_PWD_1;
	private static String KB_USER_2;
	private static String KB_PWD_2;
	
	private static AbstractHandleClient HANDLE_CLI;
	
	private static final ObjectMapper SORTED_MAPPER = new ObjectMapper();
	static {
		SORTED_MAPPER.configure(
				SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	}
	
	@BeforeClass
	public static void setUpClass() throws Exception {
		Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);
		JGI_USER = System.getProperty("test.jgi.user");
		JGI_PWD = System.getProperty("test.jgi.pwd");
		KB_USER_1 = System.getProperty("test.kbase.user1");
		KB_PWD_1 = System.getProperty("test.kbase.pwd1");
		KB_USER_2 = System.getProperty("test.kbase.user2");
		KB_PWD_2 = System.getProperty("test.kbase.pwd2");
		
		HANDLE_CLI = new AbstractHandleClient(
				new URL(HANDLE_URL), KB_USER_1, KB_PWD_1);
		HANDLE_CLI.setIsInsecureHttpConnectionAllowed(true);
		HANDLE_CLI.setAllSSLCertificatesTrusted(true);
		
		String wipeUser = System.getProperty("test.kbase.wipe_user");
		String wipePwd = System.getProperty("test.kbase.wipe_pwd");
		WipeDev03Client wipe = new WipeDev03Client(new URL(WIPE_URL), wipeUser,
				wipePwd);
		wipe.setIsInsecureHttpConnectionAllowed(true);
		wipe.setAllSSLCertificatesTrusted(true);
		wipe.setConnectionReadTimeOut(60000);
		if (!SKIP_WIPE) {
			System.out.print("triggering remote wipe of test data stores... ");
			Tuple2<Long, String> w = wipe.wipeDev03();
			if (w.getE1() > 0 ) {
				throw new TestException(
						"Wipe of test server failed. The wipe server said:\n" +
								w.getE2());
			}
			System.out.println("done. Server said:\n" + w.getE2());
		}
	}
	
	private static class JGIOrganismPage {
		
		private final static String JGI_SIGN_ON =
				"https://signon.jgi.doe.gov/signon";
		
		private final static String JGI_ORGANISM_PAGE =
				"http://genomeportal.jgi.doe.gov/pages/dynamicOrganismDownload.jsf?organism=";
		
		private final String organismCode;
		private HtmlPage page;
		private final Set<JGIFileLocation> selected =
				new HashSet<JGIFileLocation>();

		public JGIOrganismPage(
				WebClient client,
				String organismCode,
				String JGIuser,
				String JGIpwd)
				throws Exception {
			super();
			if (JGIuser == null) {
				System.out.println("Skipping JGI login, user is null");
			} else {
				System.out.println(String.format("Signing on to JGI at %s...",
						new Date()));
				signOnToJGI((HtmlPage) client.getPage(JGI_SIGN_ON),
						JGIuser, JGIpwd);
				System.out.println(String.format("Signed on to JGI at %s.",
						new Date()));
			}
			System.out.println(String.format("Opening %s page at %s... ",
					organismCode, new Date()));
			this.organismCode = organismCode;
			this.page = client.getPage(JGI_ORGANISM_PAGE + organismCode);
			Thread.sleep(3000); // wait for page & file table to load
			//TODO WAIT: necessary? find a better way to check page is loaded
			System.out.println(String.format("Opened %s page at %s.",
					organismCode, new Date()));
			closePushedFilesDialog(false);
		}
		
		private void signOnToJGI(HtmlPage signonPage, String user, String password)
				throws IOException, MalformedURLException {
			assertThat("Signon title ok", signonPage.getTitleText(),
					is("JGI Single Sign On"));
			try {
				signonPage.getHtmlElementById("highlight-me");
				fail("logged in already");
			} catch (ElementNotFoundException enfe) {
				//we're all good
			}

			//login form has no name, which is the only way to get a specific form
			List<HtmlForm> forms = signonPage.getForms();
			assertThat("2 forms on login page", forms.size(), is(2));
			HtmlForm form = forms.get(1);
			form.getInputByName("login").setValueAttribute(user);
			form.getInputByName("password").setValueAttribute(password);
			HtmlPage loggedIn = form.getInputByName("commit").click();
			HtmlDivision div = loggedIn.getHtmlElementById("highlight-me");
			assertThat("signed in correctly", div.getTextContent().trim(),
					is("You have signed in successfully."));
		}
		
		public void selectFile(JGIFileLocation file)
				throws IOException, InterruptedException {
			selectFile(file, true);
		}
		
		public void selectFile(JGIFileLocation file, boolean select)
				throws IOException, InterruptedException {
			//text element with the file group name
			String selstr = select ? "Select" : "Unselect";
			System.out.println(String.format("%sing file %s from group %s",
					selstr, file.getFile(), file.getGroup()));
			DomElement fileText = findFile(file);
			
			HtmlCheckBoxInput filetoggle = (HtmlCheckBoxInput)
					((DomElement) fileText
					.getParentNode() //i
					.getParentNode() //a
					.getParentNode() //span
					.getParentNode()) //td
					.getElementsByTagName("input").get(0);
			
			if (select == filetoggle.getCheckedAttribute().equals("checked")) {
				return;
			}
			this.page = filetoggle.click();
			if (select) {
				selected.add(file);
			} else {
				selected.remove(file);
			}
			Thread.sleep(1000); //every click gets sent to the server
			System.out.println(String.format("%sed file %s from group %s.",
					selstr, file.getFile(), file.getGroup()));
		}
		
		private DomElement findFile(JGIFileLocation file)
				throws IOException, InterruptedException {
			DomElement fileGroup = openFileGroup(file);
			//this is ugly but it doesn't seem like there's another way
			//to get the node
			DomElement selGroup = null;
			List<HtmlElement> bold = fileGroup.getElementsByTagName("b");
			for (HtmlElement de: bold) {
				if (file.getFile().equals(de.getTextContent())) {
					selGroup = de;
					break;
				}
			}
			if (selGroup == null) {
				throw new TestException(String.format(
						"There is no file %s in file group %s for the organism %s",
						file.getFile(), file.getGroup(), organismCode));
			}
			return selGroup;
		}

		private DomElement openFileGroup(JGIFileLocation file)
				throws IOException, InterruptedException {
			int timeoutSec = 60;
			System.out.println(String.format("Opening file group %s at %s... ",
					file.getGroup(), new Date()));
			
			DomElement fileGroupText = findFileGroup(file);
			DomElement fileContainer = getFilesDivFromFilesGroup(
					fileGroupText);
			
			if (fileContainer.isDisplayed()) {
				System.out.println(String.format("File group %s already open.",
						file.getGroup()));
				return fileContainer;
			}
					
			HtmlAnchor fileSetToggle = (HtmlAnchor) fileGroupText
					.getParentNode() //td
					.getPreviousSibling() //td folder icon
					.getPreviousSibling() //td toggle icon
					.getChildNodes().get(0) //div
					.getChildNodes().get(0); //a
			
			this.page = fileSetToggle.click();
			
			Long startNanos = System.nanoTime(); 
			while (!fileContainer.isDisplayed()) {
				fileGroupText = findFileGroup(file);
				fileContainer = getFilesDivFromFilesGroup(fileGroupText);
				checkTimeout(startNanos, timeoutSec, String.format(
						"Timed out waiting for file group %s to open after %s seconds",
						file.getGroup(), timeoutSec));
				Thread.sleep(1000);
			}
			System.out.println(String.format("Opened file group %s at %s.",
					file.getGroup(), new Date()));
			return fileContainer;
		}

		public void pushToKBase(String user, String pwd)
				throws IOException, InterruptedException {
			System.out.println(String.format("Pushing files to KBase at %s...",
					new Date()));

			List<?> pushlist =  page.getByXPath(
					"//input[contains(@class, 'pushToKbaseClass')]");
			assertThat("only 1 pushToKbaseClass", pushlist.size(), is(1));
			
			HtmlInput push = (HtmlInput) pushlist.get(0);
			
			this.page = push.click();
			Thread.sleep(1000); // just in case, should be fast to create modal
			
			HtmlForm kbLogin = page.getFormByName("form"); //interesting id there
			kbLogin.getInputByName("user_id").setValueAttribute(user);
			kbLogin.getInputByName("password").setValueAttribute(pwd);

			HtmlAnchor loginButton = (HtmlAnchor) kbLogin
					.getParentNode() //p
					.getParentNode() //div
					.getNextSibling() //div
					.getFirstChild() //div
					.getChildNodes().get(1) //div
					.getChildNodes().get(1); //a
			this.page = loginButton.click();

			checkPushedFiles();
			closePushedFilesDialog(true);
			for (JGIFileLocation file: selected) {
				selectFile(file, false); //reset all toggles to unselected state
			}
			System.out.println(String.format("Finished push to KBase at %s.",
					new Date()));
		}

		private void closePushedFilesDialog(boolean failIfClosedNow)
				throws IOException, InterruptedException {
			HtmlElement resDialogDiv = (HtmlElement) page.getElementById(
							"downloadForm:showFilesPushedToKbaseContentTable");
			if (failIfClosedNow) {
				assertThat("result dialog open", resDialogDiv.isDisplayed(),
						is(true));
			} else {
				if (!resDialogDiv.isDisplayed()) {
					return;
				}
			}
			HtmlInput ok = (HtmlInput) resDialogDiv
					.getFirstChild() //tbody
					.getFirstChild() //tr
					.getFirstChild() //td
					.getFirstChild() //div
					.getChildNodes().get(2) //div
					.getFirstChild(); //input

			page = ok.click();
			Thread.sleep(2000);
			
			resDialogDiv = (HtmlElement) page.getElementById(
							"downloadForm:showFilesPushedToKbaseContentTable");
			assertThat("Dialog closed", resDialogDiv.isDisplayed(), is(false));
		}

		public String getWorkspaceName(String user) {
			DomNode foldable = page.getElementById("foldable");
			DomNode projName = foldable
					.getFirstChild()
					.getFirstChild()
					.getChildNodes().get(1);
			return projName.getTextContent()
					.replace(" ", "_")
					.replace("-", "")
					.replace(".", "")
					+ '_' + user;
		}

		private void checkPushedFiles() throws InterruptedException {
			// make this configurable per test?
			//may need to be longer for tape files
			Set<String> filesFound = getPushedFileList("acceptedFiles");
			Set<String> filesRejected = getPushedFileList("rejectedFiles");
			Set<String> filesExpected = new HashSet<String>();
			Set<String> rejectedExpected = new HashSet<String>();
			for (JGIFileLocation file: selected) {
				if (file.isExpectedRejection()) {
					rejectedExpected.add(file.getFile());
				} else {
					filesExpected.add(file.getFile());
				}
			}

			assertThat("correct files in accept dialog", filesFound,
					is(filesExpected));
			assertThat("correct files in reject dialog", filesRejected,
					is(rejectedExpected));
		}

		private Set<String> getPushedFileList(String elementID)
				throws InterruptedException {
			int timeoutSec = 20;
			
			HtmlElement resDialogDiv =
					(HtmlElement) page.getElementById(elementID);
			DomNode bodyParent = resDialogDiv
					.getParentNode()  //ul
					.getParentNode()  //div
					.getParentNode();  //div modal-body
			Long startNanos = System.nanoTime();
			while (!bodyParent.isDisplayed()) {
				checkTimeout(startNanos, timeoutSec, String.format(
						"Timed out waiting for files to push to Kbase after %s seconds",
						timeoutSec));
				Thread.sleep(1000);
			}
			String[] splDialog = resDialogDiv.getTextContent().split("\n");
			Set<String> filesFound = new HashSet<String>();

			for (int i = 0; i < splDialog.length; i++) {
				if (splDialog[i].length() > 0) {
					filesFound.add(splDialog[i]);
				}
			}
			return filesFound;
		}

		private DomElement getFilesDivFromFilesGroup(DomElement selGroup) {
			return (DomElement) selGroup
					.getParentNode() //td
					.getParentNode() //tr
					.getParentNode() //tbody
					.getParentNode() //table
					.getNextSibling(); //div below table
		}

		private DomElement findFileGroup(JGIFileLocation file) {
			//this is ugly but it doesn't seem like there's another way
			//to get the node
			DomElement selGroup = null;
			List<DomElement> bold = page.getElementsByTagName("b");
			for (DomElement de: bold) {
				if (file.getGroup().equals(de.getTextContent())) {
					selGroup = de;
					break;
				}
			}
			if (selGroup == null) {
				throw new TestException(String.format(
						"There is no file group %s for the organism %s",
						file.getGroup(), organismCode));
			}
			return selGroup;
		}
	}
	
	private static class JGIFileLocation {
		private final String group;
		private final String file;
		private final boolean expectReject;
		
		public JGIFileLocation(String group, String file) {
			this(group, file, false);
		}
		
		/** The location of a file on a JGI genome portal page.
		 * @param group the file group containing the file
		 * @param file the name of the file
		 * @param expectRejection true if the file should be rejected for
		 * pushing to KBase.
		 */
		public JGIFileLocation(String group, String file,
				boolean expectRejection) {
			this.group = group;
			this.file = file;
			this.expectReject = expectRejection;
		}
		
		public String getGroup() {
			return group;
		}
		
		public String getFile() {
			return file;
		}
		
		public boolean isExpectedRejection() {
			return expectReject;
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("JGIFileLocation [group=");
			builder.append(group);
			builder.append(", file=");
			builder.append(file);
			builder.append(", expectReject=");
			builder.append(expectReject);
			builder.append("]");
			return builder.toString();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + (expectReject ? 1231 : 1237);
			result = prime * result + ((file == null) ? 0 : file.hashCode());
			result = prime * result + ((group == null) ? 0 : group.hashCode());
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
			JGIFileLocation other = (JGIFileLocation) obj;
			if (expectReject != other.expectReject)
				return false;
			if (file == null) {
				if (other.file != null)
					return false;
			} else if (!file.equals(other.file))
				return false;
			if (group == null) {
				if (other.group != null)
					return false;
			} else if (!group.equals(other.group))
				return false;
			return true;
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
		
		public TestResult(String shockID, String shockURL, String handleID) {
			super();
			this.shockID = shockID;
			this.shockURL = shockURL;
			this.handleID = handleID;
		}

		@SuppressWarnings("unused")
		public String getShockID() {
			return shockID;
		}

		@SuppressWarnings("unused")
		public String getShockURL() {
			return shockURL;
		}

		@SuppressWarnings("unused")
		public String getHandleID() {
			return handleID;
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
	public void pushTwoFiles() throws Exception {
		TestSpec tspec = new TestSpec("AlimarDSM23064", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6501.2.45840.GCAAGG.adnq.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"ff117914a28ffa48b520707e89fa683c"));
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("Raw Data",
						"6501.2.45840.GCAAGG.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"ff117914a28ffa48b520707e89fa683c"));
		runTest(tspec);
	}
	
	@Ignore
	@Test
	public void pushTwoFilesSameGroup() throws Exception {
		//never mind - no PtKB for this project any more
		//TODO update this test once assy & annot are ready or if find project with multiple reads in one folder
		TestSpec tspec = new TestSpec("EsccolMEco_fish4", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("Raw Data",
						"2402.6.1921.ATTCCT.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo"));
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("Raw Data",
						"2402.7.1921.ATTCCT.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo"));
		runTest(tspec);
	}
	
	@Test
	public void pushSameFileWithSameClient() throws Exception {
		//TODO need to reuse the page, since the page is never closed the toggles aren't reset
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
		
		@SuppressWarnings("unused")
		Map<FileSpec, TestResult> res1 = checkResults(tspec1, wsName);
		
		processTestSpec(tspec2, cli, new CollectingAlertHandler(alerts), true);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 2",
				new Date(), getTestMethodName()));
		
		@SuppressWarnings("unused")
		Map<FileSpec, TestResult> res2 = checkResults(tspec2, wsName);
		
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		//TODO WAIT: reinstate this if fixed - currently different shock nodes are created
//		assertThat("Pushing same file twice uses same shock node",
//				res2.get(fs2), is(res1.get(fs1)));
	}
	
	@Test
	public void pushSameFileWithDifferentClient() throws Exception {
		FileSpec fs1 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6622.1.49213.GTGAAA.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"43595f98c55720b7d378eb8e5854e27b");
		
		FileSpec fs2 = new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6622.1.49213.GTGAAA.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 2L,
				"43595f98c55720b7d378eb8e5854e27b");
		
		TestSpec tspec1 = new TestSpec("CycspSCAC281A15", KB_USER_1, KB_PWD_1);
		tspec1.addFileSpec(fs1);
		
		TestSpec tspec2 = new TestSpec("CycspSCAC281A15", KB_USER_1, KB_PWD_1);
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
		
		@SuppressWarnings("unused")
		Map<FileSpec, TestResult> res1 = checkResults(tspec1, wsName);
		
		cli = new WebClient(); //this is the only major difference from the same client test
		processTestSpec(tspec2, cli, new CollectingAlertHandler(alerts), false);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 2",
				new Date(), getTestMethodName()));
		
		@SuppressWarnings("unused")
		Map<FileSpec, TestResult> res2 = checkResults(tspec2, wsName);
		
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		//TODO WAIT: reinstate this if fixed - currently different shock nodes are created
//		assertThat("Pushing same file twice uses same shock node",
//				res2.get(fs2), is(res1.get(fs1)));
	}
	
	@Test
	public void pushSameFileDifferentUsers() throws Exception {
		//TODO need to reuse the page, since the page is never closed the toggles aren't reset
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
		
		@SuppressWarnings("unused")
		Map<FileSpec, TestResult> res1 = checkResults(tspec1, wsName);
		
		wsName = processTestSpec(tspec2, cli, new CollectingAlertHandler(alerts), true);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s part 2",
				new Date(), getTestMethodName()));
		
		@SuppressWarnings("unused")
		Map<FileSpec, TestResult> res2 = checkResults(tspec2, wsName);
		
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		assertThat("No alerts triggered", alerts.isEmpty(), is(true));
		//TODO WAIT: reinstate this if fixed - currently different shock nodes are created
//		assertThat("Pushing same file twice uses same shock node",
//				res2.get(fs2), is(res1.get(fs1)));
	}
	
	//TODO WAIT: push assembly and annotation files when available
	
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
		TestSpec tspec = new TestSpec("ColspSCAC281B05", KB_USER_1, KB_PWD_1);
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"6622.1.49213.CGTACG.adnq.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"foo"),
				true); //unselect after selecting
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("Raw Data",
						"6622.1.49213.CGTACG.fastq.gz"),
						"KBaseFile.PairedEndLibrary-2.1", 1L,
						"9ca6a9fe6cdfa32f417a9c1aa24c5409"));
		List<String> alerts = new LinkedList<String>();
		String wsName = runTest(tspec, new CollectingAlertHandler(alerts));
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		
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
		TestSpec tspec = new TestSpec("ColspSCAC281B05", KB_USER_1, KB_PWD_1); //if parallelize, change to unused page
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
		TestSpec tspec = new TestSpec("AllhisDSM15230", KB_USER_1, KB_PWD_1); //if parallelize, change to unused page
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
		
		
		runTest(tspec);
	}
	
	private String runTest(TestSpec tspec) throws Exception {
		List<String> alerts = new LinkedList<String>();
		String wsName = runTest(tspec, new CollectingAlertHandler(alerts));
		assertThat("No alerts triggered", alerts.isEmpty(), is (true));
		return wsName;
	}

	private String runTest(TestSpec tspec, AlertHandler handler)
			throws Exception {
		System.out.println("Starting test " + getTestMethodName());
		Date start = new Date();
		WebClient cli = new WebClient();
		String wsName = processTestSpec(tspec, cli, handler, false);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s",
				new Date(), getTestMethodName()));
		
		checkResults(tspec, wsName);
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
		System.out.println();
		return wsName;
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
					if (!se.getMessage().contains("cannot be accessed")) {
						System.out.println(String.format(
								"Got fatal exception at %s:", new Date()));
						System.out.println(se.getMessage());
						throw se;
					} //otherwise try again
					Thread.sleep(PUSH_TO_WS_SLEEP_SEC * 1000);
				}
			}
			System.out.println(String.format(
					"Retrived file from workspace after %s seconds",
					((System.nanoTime() - start) / 1000000000)));
			res.put(fs, checkResults(wsObj, tspec, fs));
		}
		
		assertNoIllegalFilesPushed(tspec, workspace, wsClient);
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
		fail("got unacceptable exception from workspace: " + message);
	}

	private TestResult checkResults(
			ObjectData wsObj, TestSpec tspec, FileSpec fs)
			throws Exception {
		//TODO this will need to be type specific - different for assembly, annotation
		System.out.println(String.format("checking file " + fs.getLocation()));
		@SuppressWarnings("unchecked")
		Map<String, Object> data = wsObj.getData().asClassInstance(Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> lib1 = (Map<String, Object>) data.get("lib1");
		@SuppressWarnings("unchecked")
		Map<String, Object> file = (Map<String, Object>) lib1.get("file");
		String hid = (String) file.get("hid");
		String shockID = (String) file.get("id");
		String url = (String) file.get("url");
		file.put("hid", "dummy");
		file.put("id", "dummy");
		file.put("url", "dummy");
		Map<String,String> meta = wsObj.getInfo().getE11();
		saveWorkspaceObjectAndMeta(tspec, fs, data, meta);
		
		Map<String, Object> expectedData = loadWorkspaceObject(tspec, fs);
		Map<String, String> expectedMeta = loadWorkspaceObjectMeta(tspec, fs);
		
		ObjectMapper mapper = new ObjectMapper();
		JsonNode datadiff = JsonDiff.asJson(mapper.valueToTree(expectedData), 
				mapper.valueToTree(data));
		JsonNode metadiff = JsonDiff.asJson(mapper.valueToTree(expectedMeta), 
				mapper.valueToTree(meta));
		
		if (datadiff.size() != 0) {
			//TODO print file
			System.out.println("Workspace object changed:");
			System.out.println(datadiff);
		}
		
		if (metadiff.size() != 0) {
			//TODO print file
			System.out.println("Workspace object metadata changed:");
			System.out.println(metadiff);
		}
		
		//TODO WAIT: test provenance when added

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
				datadiff.size(), is(0));
		assertThat("object type correct", wsObj.getInfo().getE3(),
				is(fs.getType()));
		if (!SKIP_VERSION_ASSERT) {
			assertThat("correct version of object", wsObj.getInfo().getE5(),
					is(fs.getExpectedVersion()));
		}
		assertThat("handle type correct", h.getType(), is("shock"));
		assertThat("handle hid correct", h.getHid(), is(hid));
		assertThat("handle shock id correct", h.getId(), is(shockID));
		assertThat("handle url correct", h.getUrl(), is(url));
		//can't check ACLs, can only check that file is accessible
		//need to be owner to see ACLs
		assertThat("Shock file md5 correct",
				node.getFileInformation().getChecksum("md5"),
				is(fs.getShockMD5()));
		return new TestResult(shockID, url, hid);
	}

	private void saveWorkspaceObjectAndMeta(TestSpec tspec, FileSpec fs,
			Map<String, Object> wsdata, Map<String, String> wsmeta)
			throws Exception {
		if (!SAVE_WS_OBJECTS) {
			return;
		}
		writeObjectAsJsonToFile(wsdata, tspec, fs, ".json");
		
		Map<String, Object> meta = new HashMap<String, Object>();
		meta.putAll(wsmeta);
		
		writeObjectAsJsonToFile(meta, tspec, fs, ".meta.json");
	}

	private void writeObjectAsJsonToFile(Map<String, Object> data,
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
		return readJsonFromFile(tspec, fs, ".json");
	}

	private Map<String, String> loadWorkspaceObjectMeta(TestSpec tspec,
			FileSpec fs) throws Exception {
		Map<String, Object> wsmeta = readJsonFromFile(tspec, fs, ".meta.json");
		Map<String, String> meta = new HashMap<String, String>();
		for (Entry<String, Object> entry: wsmeta.entrySet()) {
			meta.put(entry.getKey(), (String) entry.getValue());
		}
		return meta;
	}

	private Map<String, Object> readJsonFromFile(TestSpec tspec, FileSpec fs,
			String extension) throws Exception {
		Path p = getSavedDataFilePath(tspec, fs, extension);
		BufferedReader reader = Files.newBufferedReader(p,
				Charset.forName("UTF-8"));
		@SuppressWarnings("unchecked")
		Map<String, Object> data = new ObjectMapper()
				.readValue(reader, Map.class);
		return data;
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