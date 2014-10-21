package us.kbase.jgiintegration.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.CollectingAlertHandler;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.DomNode;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlCheckBoxInput;
import com.gargoylesoftware.htmlunit.html.HtmlDivision;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlSubmitInput;

public class JGIIntegrationTest {
	
	private final static String JGI_SIGN_ON =
			"https://signon.jgi.doe.gov/signon";
	
	//if this could be parameterized it'd be nice
	private final static String JGI_ORGANISM_PAGE =
			"http://genomeportal.jgi.doe.gov/pages/dynamicOrganismDownload.jsf?organism=BlaspURHD0036";
	
	private final static String JGI_QC_RAW_TOGGLE =
			"downloadForm:j_id152:nodeId__ALL__JAMO__0__:nodeId__ALL__JAMO__0__1__::j_id174";

	private final static String JGI_RAW_TOGGLE =
			"downloadForm:j_id150:nodeId__ALL__JAMO__0__:nodeId__ALL__JAMO__0__3__::j_id172";
	
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
		
		private final static String JGI_ORGANISM_PAGE =
				"http://genomeportal.jgi.doe.gov/pages/dynamicOrganismDownload.jsf?organism=";
		
		private final String organismCode;
		private HtmlPage page;
		private final WebClient client;

		public JGIOrganismPage(WebClient client, String organismCode)
				throws Exception {
			super();
			this.client = client;
			this.organismCode = organismCode;
			this.page = this.client.getPage(JGI_ORGANISM_PAGE + organismCode);
			Thread.sleep(1000); // wait for page & file table to load
			//TODO should probably find a better way to check page is loaded
		}
		
		public String getOrganismCode() {
			return organismCode;
		}
		
		public void selectFile(JGIFileLocation file) throws
				IOException, InterruptedException {
			DomElement selGroup = findFileGroup(file);
			DomNode filesDiv = getFilesDivFromFilesGroup(selGroup);
			System.out.println("_____________________________");
			System.out.println(filesDiv.asXml());
			System.out.println("_____________________________");
			
			HtmlAnchor filesToggle = (HtmlAnchor) selGroup
					.getParentNode() //td
					.getPreviousSibling() //td folder icon
					.getPreviousSibling() //td toggle icon
					.getChildNodes().get(0) //div
					.getChildNodes().get(0); //a
			
			System.out.println("__________filestoggle___________________");
			System.out.println(filesToggle.asXml());
			System.out.println("_____________________________");
					
			this.page = filesToggle.click();
			Thread.sleep(1000);
			selGroup = findFileGroup(file);
			filesDiv = getFilesDivFromFilesGroup(selGroup);
			System.out.println("_____________________________");
			System.out.println(filesDiv.asXml());
			System.out.println("_____________________________");
			
			
		}

		private DomNode getFilesDivFromFilesGroup(DomElement selGroup) {
			DomNode filesDiv = selGroup
					.getParentNode() //td
					.getParentNode() //tr
					.getParentNode() //tbody
					.getParentNode() //table
					.getNextSibling(); //div below table
			return filesDiv;
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
		System.out.println("Running import -> assembly integration test");
		WebClient cli = new WebClient();
		//TODO ZZ: if JGI fixes their login page remove next line
		cli.getOptions().setThrowExceptionOnScriptError(false); 
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
		form.getInputByName("login").setValueAttribute(JGI_USER);
		form.getInputByName("password").setValueAttribute(JGI_PWD);
		HtmlPage loggedIn = form.getInputByName("commit").click();
		HtmlDivision div = loggedIn.getHtmlElementById("highlight-me");
		assertThat("signed in correctly", div.getTextContent(),
				is("You have signed in successfully."));

		//ok, push the data to kbase
		JGIOrganismPage org = new JGIOrganismPage(cli, "BlaspURHD0036");
//		HtmlPage organism = cli.getPage(JGI_ORGANISM_PAGE);
//		System.out.println(organism.asXml());
		
		//TODO detect file tree has loaded
		Thread.sleep(1000); // wait for file tree to load
		
		//TODO add more data types here and check later
		JGIFileLocation qcReads = new JGIFileLocation("QC Filtered Raw Data",
				"6501.2.45840.GCAAGG.adnq.fastq.gz");
		org.selectFile(qcReads);
//		HtmlCheckBoxInput toggle = organism.getElementByName(JGI_QC_RAW_TOGGLE);
//		toggle.click();
//		
//		List<String> alerts = new LinkedList<String>();
//		cli.setAlertHandler(new CollectingAlertHandler(alerts));
//		
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
