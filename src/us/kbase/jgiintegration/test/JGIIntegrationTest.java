package us.kbase.jgiintegration.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.abstracthandle.AbstractHandleClient;
import us.kbase.abstracthandle.Handle;
import us.kbase.auth.AuthService;
import us.kbase.common.service.ServerException;
import us.kbase.common.utils.MD5DigestOutputStream;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.ShockNode;
import us.kbase.shock.client.ShockNodeId;
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
	
	//TODO add more data types other than reads
	//TODO add a list of files (in another suite? - factor out the common test code)
	//TODO test with nothing selected: use code like:
	/*
	 * List<String> alerts = new LinkedList<String>();
	 * cli.setAlertHandler(new CollectingAlertHandler(alerts));
	 */
	
	private static String JGI_USER;
	private static String JGI_PWD;
	private static String KB_USER_1;
	private static String KB_PWD_1;
	
	private static final int PUSH_TO_WS_TIMEOUT_SEC = 20 * 60; //20min
	private static final int PUSH_TO_WS_SLEEP_SEC = 5;
	
	private static WorkspaceClient WS_CLI1;
	private static AbstractHandleClient HANDLE_CLI;
	
	//TODO parameterize
	private static String WS_URL =
			"https://dev03.berkeley.kbase.us/services/ws";
	private static String HANDLE_URL = 
			"https://dev03.berkeley.kbase.us/services/handle_service";
	
	private static final ObjectMapper SORTED_MAPPER = new ObjectMapper();
	static {
		SORTED_MAPPER.configure(
				SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	}
	
	@BeforeClass
	public static void setUpClass() throws Exception {
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
		//TODO wipe dev03
	}
	
	private class JGIOrganismPage {
		
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
			this.client = client;
			signOnToJGI(client, JGIuser, JGIpwd);
			this.organismCode = organismCode;
			this.page = this.client.getPage(JGI_ORGANISM_PAGE + organismCode);
			Thread.sleep(3000); // wait for page & file table to load
			//TODO find a better way to check page is loaded
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
			DomElement fileGroupText = findFileGroup(file);

			HtmlAnchor fileSetToggle = (HtmlAnchor) fileGroupText
					.getParentNode() //td
					.getPreviousSibling() //td folder icon
					.getPreviousSibling() //td toggle icon
					.getChildNodes().get(0) //div
					.getChildNodes().get(0); //a
			
			this.page = fileSetToggle.click();
			Thread.sleep(5000); //load file names, etc.
			//TODO check that file names are loaded
			//TODO is this toggling the files off if run twice
			
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
			

			//TODO test periodically with a timeout, needs to be long for tape
			Thread.sleep(5000);
			checkPushedFiles();
			
			//TODO click ok and check results
		}

		public String getWorkspaceName(String user) {
			DomNode foldable = page.getElementById("foldable");
			DomNode projName = foldable
					.getFirstChild()
					.getFirstChild()
					.getChildNodes().get(1);
			return projName.getTextContent().replace(' ', '_') + '_' + user;
		}

		private void checkPushedFiles() {
			HtmlElement resDialogDiv =
					(HtmlElement) page.getElementById("filesPushedToKbase");
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
		
		//file must be visible prior to calling this method
		private DomElement findFile(JGIFileLocation file) {
			DomElement fileGroupText = findFileGroup(file);
			DomElement fileGroup = getFilesDivFromFilesGroup(fileGroupText);
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
	}
	
	private class JGIFileLocation {
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
	
	@SuppressWarnings("serial")
	public class TestException extends RuntimeException {

		public TestException(String msg) {
			super(msg);
		}
	}
	
	@Test
	public void fullIntegration() throws Exception {
		WebClient cli = new WebClient();
		//TODO ZZ: if JGI fixes login page remove next line
		cli.getOptions().setThrowExceptionOnScriptError(false);
		cli.setAlertHandler(new AlertHandler() {
			
			@Override
			public void handleAlert(Page arg0, String arg1) {
				throw new TestException("Unexpected alert: " + arg1);
				
			}
		});
		//MD5 of object when variable fields are replaced by "dummy"
		final String organismCode = "BlaspURHD0036";
		final String fileGroup = "QC Filtered Raw Data";
		String fileName = "7625.2.79179.AGTTCC.adnq.fastq.gz";
		final String type = "KBaseFile.PairedEndLibrary-2.1";
		final long expectedVersion = 1;
		String workspaceDummyMD5 = "39db907edfb9ba1861b5402201b72ada";
		String shockMD5 = "5c66abbb2515674a074d2a41ecf01017";
		
		JGIOrganismPage org = new JGIOrganismPage(cli, organismCode,
				JGI_USER, JGI_PWD);
		
		JGIFileLocation qcReads = new JGIFileLocation(fileGroup,
				fileName);
		org.selectFile(qcReads);
		
		org.pushToKBase(KB_USER_1, KB_PWD_1);
		String wsName = org.getWorkspaceName(KB_USER_1); 
		
		
		Long start = System.nanoTime();
		ObjectData wsObj = null;
		while(wsObj == null) {
			checkTimeout(start, PUSH_TO_WS_TIMEOUT_SEC, String.format(
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
		MD5DigestOutputStream md5out = new MD5DigestOutputStream();
		SORTED_MAPPER.writeValue(md5out, data);
		assertThat("correct md5 for workspace object",
				md5out.getMD5().getMD5(), is(workspaceDummyMD5));
		//TODO check metadata
		//TODO test provenance when added
		
		Handle h = HANDLE_CLI.hidsToHandles(Arrays.asList(hid)).get(0);
		assertThat("handle type correct", h.getType(), is("shock"));
		assertThat("handle hid correct", h.getHid(), is(hid));
		assertThat("handle shock id correct", h.getId(), is(shockID));
		assertThat("handle url correct", h.getUrl(), is(url));
		
		
		assertThat("correct version of object", wsObj.getInfo().getE5(), is(expectedVersion));
		assertThat("object type correct", wsObj.getInfo().getE3(),
				is(type));
		
		BasicShockClient shock = new BasicShockClient(new URL(url),
				AuthService.login(KB_USER_1, KB_PWD_1).getToken(), true);
		ShockNode node = shock.getNode(new ShockNodeId(shockID));
		//can't check ACLs, can only check that file is accessible
		//need to be owner to see ACLs
		assertThat("Shock file md5 correct",
				node.getFileInformation().getChecksum("md5"), is(shockMD5));
		
		//TODO push through assembly, at least for one test
		//TODO turn off or redirect gargolye logs
		
		cli.closeAllWindows();
	}

	private void checkTimeout(Long startNanos, int timeoutSec, String message) {
		if ((System.nanoTime() - startNanos) / 1000000000 > timeoutSec) {
			throw new TestException(message);
		}
		
	}
}