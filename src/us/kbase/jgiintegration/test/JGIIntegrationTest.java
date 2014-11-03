package us.kbase.jgiintegration.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.abstracthandle.AbstractHandleClient;
import us.kbase.abstracthandle.Handle;
import us.kbase.auth.AuthService;
import us.kbase.common.service.ServerException;
import us.kbase.common.service.Tuple2;
import us.kbase.common.utils.MD5DigestOutputStream;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.ShockNode;
import us.kbase.shock.client.ShockNodeId;
import us.kbase.wipedev03.WipeDev03Client;
import us.kbase.workspace.ObjectData;
import us.kbase.workspace.ObjectIdentity;
import us.kbase.workspace.WorkspaceClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gargoylesoftware.htmlunit.AlertHandler;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.DomNode;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlDivision;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class JGIIntegrationTest {
	
	//TODO set up automated runner with jenkins
	//TODO add more data types other than reads
	//TODO add a list of files (in another suite? - factor out the common test code)
	//TODO test with nothing selected: use code like:
	/*
	 * List<String> alerts = new LinkedList<String>();
	 * cli.setAlertHandler(new CollectingAlertHandler(alerts));
	 */
	//TODO may need to parallelize tests. If so print thread ID with all output
	
	private static String WS_URL =
			"https://dev03.berkeley.kbase.us/services/ws";
	private static String HANDLE_URL = 
			"https://dev03.berkeley.kbase.us/services/handle_service";
	private static String WIPE_URL = 
			"http://dev03.berkeley.kbase.us:9000";

	private static final int PUSH_TO_WS_TIMEOUT_SEC = 30 * 60; //30min
	private static final int PUSH_TO_WS_SLEEP_SEC = 5;
	
	//for testing
	private static final boolean SKIP_WIPE = false;
	
	private static String JGI_USER;
	private static String JGI_PWD;
	private static String KB_USER_1;
	private static String KB_PWD_1;
	
	private static WorkspaceClient WS_CLI1;
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
		WS_CLI1 = new WorkspaceClient(new URL(WS_URL), KB_USER_1, KB_PWD_1);
		WS_CLI1.setIsInsecureHttpConnectionAllowed(true);
		WS_CLI1.setAllSSLCertificatesTrusted(true);
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
		private final WebClient client;
		private final Set<JGIFileLocation> selected =
				new HashSet<JGIFileLocation>();

		public JGIOrganismPage(
				WebClient client,
				String organismCode,
				String JGIuser,
				String JGIpwd)
				throws Exception {
			super();
			System.out.println(String.format("Opening %s page at %s... ",
					organismCode, new Date()));
			this.client = client;
			signOnToJGI(client, JGIuser, JGIpwd);
			this.organismCode = organismCode;
			this.page = this.client.getPage(JGI_ORGANISM_PAGE + organismCode);
			Thread.sleep(3000); // wait for page & file table to load
			//TODO find a better way to check page is loaded
			System.out.println(String.format("Opened %s page at %s.",
					organismCode, new Date()));
			closePushedFilesDialog(false);
		}
		
		private void signOnToJGI(WebClient cli, String user, String password)
				throws IOException, MalformedURLException {
			HtmlPage hp = cli.getPage(JGI_SIGN_ON);
			assertThat("Signon title ok", hp.getTitleText(),
					is("JGI Single Sign On"));
			try {
				hp.getHtmlElementById("highlight-me");
				fail("logged in already");
			} catch (ElementNotFoundException enfe) {
				//we're all good
			}

			//login form has no name, which is the only way to get a specific form
			List<HtmlForm> forms = hp.getForms();
			assertThat("2 forms on login page", forms.size(), is(2));
			HtmlForm form = forms.get(1);
			form.getInputByName("login").setValueAttribute(user);
			form.getInputByName("password").setValueAttribute(password);
			HtmlPage loggedIn = form.getInputByName("commit").click();
			HtmlDivision div = loggedIn.getHtmlElementById("highlight-me");
			assertThat("signed in correctly", div.getTextContent(),
					is("You have signed in successfully."));
		}
		
		public void selectFile(JGIFileLocation file) throws
				IOException, InterruptedException {
			//text element with the file group name
			System.out.println(String.format("Selecting file %s from group %s",
					file.getFile(), file.getGroup()));
			DomElement fileText = findFile(file);
			
			HtmlInput filetoggle = (HtmlInput) ((DomElement) fileText
					.getParentNode() //i
					.getParentNode() //a
					.getParentNode() //span
					.getParentNode()) //td
					.getElementsByTagName("input").get(0);
			
			if (filetoggle.getCheckedAttribute().equals("checked")) {
				return;
			}
			this.page = filetoggle.click();
			selected.add(file);
			Thread.sleep(1000); //every click gets sent to the server
			System.out.println(String.format("Selected file %s from group %s.",
					file.getFile(), file.getGroup()));
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
			int timeoutSec = 20;
			System.out.println(String.format("Opening file group %s at %s... ",
					file.getGroup(), new Date()));
			
			DomElement fileGroupText = findFileGroup(file);
			DomElement fileContainer = getFilesDivFromFilesGroup(
					fileGroupText);
			
			//TODO this is not tested - test with multiple files per test
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
						"Timed out waiting for file group %s to open",
						file.getGroup()));
				Thread.sleep(1000);
			}
			System.out.println(String.format("Opened file group %s at %s.",
					file.getGroup(), new Date()));
			return fileContainer;
		}

		public void pushToKBase(String user, String pwd)
				throws IOException, InterruptedException {
			HtmlInput push = (HtmlInput) page.getElementById(
							"downloadForm:fileTreePanel")
					.getChildNodes().get(2) //div
					.getFirstChild() //div
					.getFirstChild() //table
					.getFirstChild() //tbody
					.getFirstChild() //tr
					.getChildNodes().get(1) //td
					.getFirstChild(); //input
			
			this.page = push.click();
			Thread.sleep(1000); // just in case, should be fast to create modal
			
			HtmlForm kbLogin = page.getFormByName("form"); //interesting id there
			kbLogin.getInputByName("user_id").setValueAttribute(KB_USER_1);
			kbLogin.getInputByName("password").setValueAttribute(KB_PWD_1);

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
		}

		private void closePushedFilesDialog(boolean failIfClosedNow)
				throws IOException, InterruptedException {
			HtmlElement resDialogDiv =
					(HtmlElement) page.getElementById("filesPushedToKbase");
			if (failIfClosedNow) {
				assertThat("result dialog open", resDialogDiv.isDisplayed(),
						is(true));
			} else {
				if (!resDialogDiv.isDisplayed()) {
					return;
				}
			}
			HtmlInput ok = (HtmlInput) resDialogDiv
					.getNextSibling() //br
					.getNextSibling() //br
					.getNextSibling(); //input

			page = ok.click();
			Thread.sleep(2000);
			
			resDialogDiv =
					(HtmlElement) page.getElementById("filesPushedToKbase");
			assertThat("Dialog closed", resDialogDiv.isDisplayed(), is(false));
		}

		public String getWorkspaceName(String user) {
			DomNode foldable = page.getElementById("foldable");
			DomNode projName = foldable
					.getFirstChild()
					.getFirstChild()
					.getChildNodes().get(1);
			return projName.getTextContent().replace(' ', '_') + '_' + user;
		}

		private void checkPushedFiles() throws InterruptedException {
			// make this configurable per test?
			//may need to be longer for tape files
			int timeoutSec = 20;
			
			HtmlElement resDialogDiv =
					(HtmlElement) page.getElementById("filesPushedToKbase");
			Long startNanos = System.nanoTime();
			while (!resDialogDiv.isDisplayed()) {
				checkTimeout(startNanos, timeoutSec,
						"Timed out waiting for files to push to Kbase");
				Thread.sleep(1000);
			}
			String[] splDialog = resDialogDiv.getTextContent().split("\n");
			Set<String> filesFound = new HashSet<String>();
			//skip first row
			for (int i = 1; i < splDialog.length; i++) {
				String[] filespl = splDialog[i].split("/");
				if (filespl.length > 1) {
					filesFound.add(filespl[filespl.length - 1]);
				}
			}
			Set<String> filesExpected = new HashSet<String>();
			for (JGIFileLocation file: selected) {
				filesExpected.add(file.getFile());
			}
			assertThat("correct files in dialog", filesFound,
					is(filesExpected));
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
		
		public JGIFileLocation(String group, String file) {
			super();
			this.group = group;
			this.file = file;
		}
		
		public String getGroup() {
			return group;
		}
		
		public String getFile() {
			return file;
		}
	}
	
	private static class FileSpec {
		private final JGIFileLocation location;
		private final String type;
		private final long expectedVersion;
		private final String workspaceDummyMD5;
		private final String shockMD5;
		private final String metaMD5;

		/** note the workspace dummy MD5 is the workspace object with the
		 * variable handle contents replaced by the string "dummy" 
		 */
		public FileSpec(JGIFileLocation location, String type,
				long expectedVersion, String workspaceDummyMD5,
				String shockMD5, String metaMD5) {
			super();
			this.location = location;
			this.type = type;
			this.expectedVersion = expectedVersion;
			this.workspaceDummyMD5 = workspaceDummyMD5;
			this.shockMD5 = shockMD5;
			this.metaMD5 = metaMD5;
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

		public String getWorkspaceDummyMD5() {
			return workspaceDummyMD5;
		}

		public String getShockMD5() {
			return shockMD5;
		}

		public String getMetaMD5() {
			return metaMD5;
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
			builder.append(", workspaceDummyMD5=");
			builder.append(workspaceDummyMD5);
			builder.append(", shockMD5=");
			builder.append(shockMD5);
			builder.append(", metaMD5=");
			builder.append(metaMD5);
			builder.append("]");
			return builder.toString();
		}
	}
	
	private static class TestSpec {
		private final String organismCode;
		private final List<FileSpec> filespecs =
				new LinkedList<JGIIntegrationTest.FileSpec>();
		
		public TestSpec(String organismCode) {
			this.organismCode = organismCode;
		}
		
		public void addFileSpec(FileSpec spec) {
			filespecs.add(spec);
		}

		public String getOrganismCode() {
			return organismCode;
		}

		public List<FileSpec> getFilespecs() {
			return new LinkedList<FileSpec>(filespecs);
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("TestSpec [organismCode=");
			builder.append(organismCode);
			builder.append(", filespecs=");
			builder.append(filespecs);
			builder.append("]");
			return builder.toString();
		}
	}
	
	@SuppressWarnings("serial")
	private static class TestException extends RuntimeException {

		public TestException(String msg) {
			super(msg);
		}
	}
	
	@Test
	public void fullIntegration() throws Exception {
		Date start = new Date();
		WebClient cli = new WebClient();
		//TODO ZZ: if JGI fixes login page remove next line
		cli.getOptions().setThrowExceptionOnScriptError(false);
		cli.setAlertHandler(new AlertHandler() {
			
			@Override
			public void handleAlert(Page arg0, String arg1) {
				throw new TestException("Unexpected alert: " + arg1);
				
			}
		});
		
		TestSpec tspec = new TestSpec("BlaspURHD0036");
		tspec.addFileSpec(new FileSpec(
				new JGIFileLocation("QC Filtered Raw Data",
						"7625.2.79179.AGTTCC.adnq.fastq.gz"),
				"KBaseFile.PairedEndLibrary-2.1", 1L,
				"39db907edfb9ba1861b5402201b72ada",
				"5c66abbb2515674a074d2a41ecf01017",
				"fde4d276a844665c46b0a140c32b5f9e"));
//		final String organismCode = "BlaspURHD0036";
//		final String fileGroup = "QC Filtered Raw Data";
//		String fileName = "7625.2.79179.AGTTCC.adnq.fastq.gz";
//		final String type = "KBaseFile.PairedEndLibrary-2.1";
//		final long expectedVersion = 1;
//		//MD5 of object when variable fields are replaced by "dummy"
//		String workspaceDummyMD5 = "39db907edfb9ba1861b5402201b72ada";
//		String shockMD5 = "5c66abbb2515674a074d2a41ecf01017";
//		String metaMD5 = "fde4d276a844665c46b0a140c32b5f9e";
		
		JGIOrganismPage org = new JGIOrganismPage(cli, tspec.getOrganismCode(),
				JGI_USER, JGI_PWD);
		
//		JGIFileLocation qcReads = new JGIFileLocation(fileGroup,
//				fileName);
		for (FileSpec fs: tspec.getFilespecs()) {
			org.selectFile(fs.getLocation());
		}
		
		org.pushToKBase(KB_USER_1, KB_PWD_1);
		System.out.println(String.format(
				"Finished push at UI level at %s for test %s",
				new Date(), getMethodName()));
		String wsName = org.getWorkspaceName(KB_USER_1); 
		
		//TODO make this a method
		Long startRetrieve = System.nanoTime();
		ObjectData wsObj = null;
		for (FileSpec fs: tspec.getFilespecs()) {
			while(wsObj == null) {
				String fileName = fs.getLocation().getFile();
				checkTimeout(startRetrieve, PUSH_TO_WS_TIMEOUT_SEC,
						String.format(
						"Timed out attempting to access object %s in workspace %s after %s sec",
						fileName, wsName, PUSH_TO_WS_TIMEOUT_SEC));
				try {
					wsObj = WS_CLI1.getObjects(Arrays.asList(new ObjectIdentity()
					.withWorkspace(wsName).withName(fileName))).get(0);
				} catch (ServerException se) {
					if (!se.getMessage().contains("cannot be accessed")) {
						throw se;
					} //otherwise try again
					Thread.sleep(PUSH_TO_WS_SLEEP_SEC * 1000);
				}
			}


			@SuppressWarnings("unchecked")
			Map<String, Object> data = wsObj.getData()
					.asClassInstance(Map.class);
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
			MD5DigestOutputStream md5out = new MD5DigestOutputStream();
			SORTED_MAPPER.writeValue(md5out, data);
			String wsObjGotMD5 = md5out.getMD5().getMD5();

			md5out = new MD5DigestOutputStream();
			Map<String,String> meta = wsObj.getInfo().getE11();
			SORTED_MAPPER.writeValue(md5out, meta);
			String metaGotMD5 = md5out.getMD5().getMD5();
			//TODO test provenance when added

			Handle h = HANDLE_CLI.hidsToHandles(Arrays.asList(hid)).get(0);

			BasicShockClient shock = new BasicShockClient(new URL(url),
					AuthService.login(KB_USER_1, KB_PWD_1).getToken(), true);
			ShockNode node = shock.getNode(new ShockNodeId(shockID));

			System.out.println("got object dummy MD5: " + wsObjGotMD5);
			System.out.println("got meta MD5: " + metaGotMD5);
			System.out.println("got shock MD5: " +
					node.getFileInformation().getChecksum("md5"));

			assertThat("correct md5 for workspace object",
					wsObjGotMD5, is(fs.getWorkspaceDummyMD5()));
			assertThat("correct md5 for metadata", metaGotMD5,
					is(fs.getMetaMD5()));
			assertThat("correct version of object", wsObj.getInfo().getE5(),
					is(fs.getExpectedVersion()));
			assertThat("object type correct", wsObj.getInfo().getE3(),
					is(fs.getType()));
			assertThat("handle type correct", h.getType(), is("shock"));
			assertThat("handle hid correct", h.getHid(), is(hid));
			assertThat("handle shock id correct", h.getId(), is(shockID));
			assertThat("handle url correct", h.getUrl(), is(url));
			//can't check ACLs, can only check that file is accessible
			//need to be owner to see ACLs
			assertThat("Shock file md5 correct",
					node.getFileInformation().getChecksum("md5"),
					is(fs.getShockMD5()));
		}
		cli.closeAllWindows();
		System.out.println("Test elapsed time: " +
				calculateElapsed(start, new Date()));
	}

	private String getMethodName() {
		Exception e = new Exception();
		e.fillInStackTrace();
		return e.getStackTrace()[1].getMethodName();
	}

	private static void checkTimeout(Long startNanos, int timeoutSec,
			String message) {
		if ((System.nanoTime() - startNanos) / 1000000000 > timeoutSec) {
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