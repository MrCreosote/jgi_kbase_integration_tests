package us.kbase.jgiintegration.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.AlertHandler;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlDivision;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class JGIIntegrationTest {
	
	//TODO add more data types other than reads
	//TODO add a list of reads (in another suite? - factor out the common test code)
	//TODO test with nothing selected: use code like:
	/*
	 * List<String> alerts = new LinkedList<String>();
	 * cli.setAlertHandler(new CollectingAlertHandler(alerts));
	 */
	
	private static String JGI_USER;
	private static String JGI_PWD;
	private static String KB_USER_1;
	private static String KB_PWD_1;
	
	@BeforeClass
	public static void setUpClass() throws Exception {
		JGI_USER = System.getProperty("test.jgi.user");
		JGI_PWD = System.getProperty("test.jgi.pwd");
		KB_USER_1 = System.getProperty("test.kbase.user1");
		KB_PWD_1 = System.getProperty("test.kbase.pwd1");
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
			Thread.sleep(2000); //load file names, etc.
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
		//TODO ZZ: if JGI fixes their login page remove next line
		cli.getOptions().setThrowExceptionOnScriptError(false);
		cli.setAlertHandler(new AlertHandler() {
			
			@Override
			public void handleAlert(Page arg0, String arg1) {
				throw new TestException(arg1);
				
			}
		});
		
		JGIOrganismPage org = new JGIOrganismPage(cli, "BlaspURHD0036",
				JGI_USER, JGI_PWD);
		
		
		JGIFileLocation qcReads = new JGIFileLocation("QC Filtered Raw Data",
				"7625.2.79179.AGTTCC.adnq.fastq.gz");
		org.selectFile(qcReads);
		
		org.pushToKBase(KB_USER_1, KB_PWD_1);
		
		cli.closeAllWindows();
	}


}
