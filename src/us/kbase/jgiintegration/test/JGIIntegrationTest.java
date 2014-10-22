package us.kbase.jgiintegration.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

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
import com.gargoylesoftware.htmlunit.html.HtmlSubmitInput;

public class JGIIntegrationTest {
	
	//TODO add more data types other than reads
	//TODO add a list of reads (in another suite? - factor out the common test code)
	//TODO test with nothing selected: use code like:
	/*
	 * List<String> alerts = new LinkedList<String>();
	 * cli.setAlertHandler(new CollectingAlertHandler(alerts));
	 */
	
	private final static String JGI_PUSH_TO_KBASE =
			"downloadForm:j_id97";

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
			Thread.sleep(2000); // wait for page & file table to load
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
		
		public String getOrganismCode() {
			return organismCode;
		}
		
		public void selectFile(JGIFileLocation file) throws
				IOException, InterruptedException {
			DomElement selGroup = findFileGroup(file);
			DomElement filesDiv = getFilesDivFromFilesGroup(selGroup);

			HtmlAnchor filesToggle = (HtmlAnchor) selGroup
					.getParentNode() //td
					.getPreviousSibling() //td folder icon
					.getPreviousSibling() //td toggle icon
					.getChildNodes().get(0) //div
					.getChildNodes().get(0); //a
			
			this.page = filesToggle.click();
			Thread.sleep(1000); //load file names, etc.
			selGroup = findFileGroup(file);
			filesDiv = getFilesDivFromFilesGroup(selGroup);
			
			DomElement fileText = findFile(filesDiv, file);
			
			HtmlInput filetoggle = (HtmlInput) ((DomElement) fileText
					.getParentNode() //i
					.getParentNode() //a
					.getParentNode() //span
					.getParentNode()) //td
					.getElementsByTagName("input").get(0);
//			
			System.out.println("[" + filetoggle.getCheckedAttribute() + "]");
			this.page = filetoggle.click();
			Thread.sleep(1000); //every click gets sent to the server
			
			System.out.println("[" + filetoggle.getCheckedAttribute() + "]");
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
		
		private DomElement findFile(
				DomElement fileGroup,
				JGIFileLocation file) {
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
		
		JGIOrganismPage org = new JGIOrganismPage(cli, "BlaspURHD0036",
				JGI_USER, JGI_PWD);
		
		
		JGIFileLocation qcReads = new JGIFileLocation("QC Filtered Raw Data",
				"7625.2.79179.AGTTCC.adnq.fastq.gz");
		org.selectFile(qcReads);
		
//		HtmlSubmitInput push = organism.getElementByName(JGI_PUSH_TO_KBASE);
//		push.click();
//		
//		System.out.println(alerts);
//		
//		HtmlForm kbLogin = hp.getFormByName("form"); //interesting id there
//		form.getInputByName("user_id").setValueAttribute(KB_USER_1);
//		form.getInputByName("password").setValueAttribute(KB_PWD_1);
		
		
		
		
		
		
		cli.closeAllWindows();
	}


}
