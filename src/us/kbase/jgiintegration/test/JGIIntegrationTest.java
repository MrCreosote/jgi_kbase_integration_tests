package us.kbase.jgiintegration.test;


import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class JGIIntegrationTest {
	//TODO ant task
	
	final String jgiSignOn = "https://signon.jgi.doe.gov/signon";
	
	//if this could be parameterized it'd be nice
	final String jgiPage = "http://genome.jgi.doe.gov/pages/dynamicOrganismDownload.jsf?organism=BlaspURHD0036";

	@Test
	public void fullIntegration() throws Exception {
		System.out.println("Running import -> assembly integration test");
		WebClient cli = new WebClient();
		//TODO ZZ: if JGI ever fixes their login page remove next line
		cli.getOptions().setThrowExceptionOnScriptError(false); 
		HtmlPage hp = cli.getPage(jgiSignOn);
		System.out.println(hp.asText());
		
	}
}
