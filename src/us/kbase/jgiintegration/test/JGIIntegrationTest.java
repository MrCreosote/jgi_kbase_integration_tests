package us.kbase.jgiintegration.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.WebClient;
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
	
	@BeforeClass
	public static void setUpClass() throws Exception {
		JGI_USER = System.getProperty("test.jgi.user");
		JGI_PWD = System.getProperty("test.jgi.pwd");
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
		HtmlPage organism = cli.getPage(JGI_ORGANISM_PAGE);
		System.out.println(organism.asXml());
		
		//TODO add more data types here and check later
		HtmlCheckBoxInput toggle = organism.getElementByName(JGI_QC_RAW_TOGGLE);
		toggle.click();
		HtmlSubmitInput push = organism.getElementByName(JGI_PUSH_TO_KBASE);
		push.click();
		
		
		
		cli.closeAllWindows();
	}
}
