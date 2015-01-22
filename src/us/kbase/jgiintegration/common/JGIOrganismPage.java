package us.kbase.jgiintegration.common;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.ScriptException;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.DomElement;
import com.gargoylesoftware.htmlunit.html.DomNode;
import com.gargoylesoftware.htmlunit.html.DomNodeList;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlCheckBoxInput;
import com.gargoylesoftware.htmlunit.html.HtmlDivision;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class JGIOrganismPage {
	//TODO use this in integration tests later
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
		this.page = loadOrganismPage(client, organismCode);
		Thread.sleep(5000); // wait for page & file table to load
		//TODO WAIT: necessary? find a better way to check page is loaded
		System.out.println(String.format(
				"Opened %s page at %s, %s characters.",
				organismCode, new Date(), page.asXml().length()));
		closePushedFilesDialog(false);
	}

	private HtmlPage loadOrganismPage(WebClient client,
			String organismCode)
			throws Exception {
		//This exception does not affect user experience or functionality
		//at all and occurs rather frequently, so we ignore it
		String acceptableExceptionContents =
				"https://issues.jgi-psf.org/rest/collectors/1.0/configuration/trigger/4c7588ab?os_authType=none&callback=trigger_4c7588ab";
		HtmlPage page = null;
		while (page == null) {
			try {
				page = client.getPage(JGI_ORGANISM_PAGE + organismCode);
			} catch (ScriptException se) {
				if (se.getMessage().contains(
						acceptableExceptionContents)) {
					System.out.println("Ignoring exception " + se);
				} else {
					throw se;
				}
			}
		}
		return page;
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
	
	//TODO filegroup class?
	public List<String> listFileGroups() {
		DomElement filetree = (DomElement)
				page.getByXPath("//div[@class='rich-tree ']").get(0);
		DomElement fileContainer = (DomElement) filetree
				.getFirstChild()
				.getChildNodes().get(2);
		for (DomNode child: fileContainer.getChildNodes()) {
			DomElement echild = (DomElement) child;
			if (echild.getTagName().equals("table")) {
				DomNodeList<HtmlElement> group =
						echild.getElementsByTagName("b");
				for (HtmlElement e: group) {
					System.out.println(e.asXml());
				}
			}
		}
		
		return null;
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
			throw new NoSuchJGIFileException(String.format(
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
					"Timed out waiting for file group %s to open after %s seconds, contents:\n%s",
					file.getGroup(), timeoutSec, fileContainer.asXml()));
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
		Set<JGIFileLocation> fixconcurrent =
				new HashSet<JGIFileLocation>(selected);
		for (JGIFileLocation file: fixconcurrent) {
			selectFile(file, false); //reset all toggles to unselected state
		}
		System.out.println(String.format("Finished push to KBase at %s.",
				new Date()));
	}

	private void closePushedFilesDialog(boolean failIfClosedNow)
			throws IOException, InterruptedException {
		HtmlElement resDialogDiv = (HtmlElement) page.getElementById(
						"downloadForm:showFilesPushedToKbaseContentTable");
		if (resDialogDiv == null) {
			System.out.println("couldn't find div for post-push dialog. Page:");
			System.out.println(page.asXml());
			fail("The post-push dialog div is not in the page as expected");
		}
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
		List<?> orgNames = page.getByXPath("//div[@class='organismName']");
		assertThat("only 1 organismName class", orgNames.size(), is(1));
		DomNode orgName = (DomNode) orgNames.get(0);
		return orgName.getTextContent()
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
		
		checkPushedFileSet("Expected", filesExpected, filesFound);
		checkPushedFileSet("Expected rejected ", filesRejected,
				rejectedExpected);
	}

	private void checkPushedFileSet(String desc, Set<String> filesExpected,
			Set<String> filesFound) {
		if (!filesFound.equals(filesExpected)) {
			System.out.println(desc + " files for push did not match actual:");
			System.out.println("Expected: " + filesExpected);
			System.out.println("Actual: " + filesFound);
			System.out.println("KBase result dialog:");
			System.out.println(getKBaseResultDialog().asXml());
			fail(desc + " files for push did not match actual: " +
					filesExpected + " vs. " + filesFound);
		}
	}

	private Set<String> getPushedFileList(String elementID)
			throws InterruptedException {
		int timeoutSec = 20;
		
		HtmlElement resDialogDiv =
				(HtmlElement) page.getElementById(elementID);
		DomNode bodyParent = getKBaseResultDialog();
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
	
	private DomNode getKBaseResultDialog() {
		HtmlElement resDialogDiv =
				(HtmlElement) page.getElementById("supportedFileTypes");
		return resDialogDiv
				.getParentNode()  //ul
				.getParentNode()  //div
				.getParentNode();  //div modal-body
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
			System.out.println(String.format(
					"There is no file group %s for the organism %s. Current page:\n%s",
					file.getGroup(), organismCode, page.asXml()));
			throw new NoSuchJGIFileGroupException(String.format(
					"There is no file group %s for the organism %s",
					file.getGroup(), organismCode));
		}
		return selGroup;
	}
	
	private static void checkTimeout(Long startNanos, int timeoutSec,
			String message) {
		if ((System.nanoTime() - startNanos) / 1000000000 > timeoutSec) {
			System.out.println(message);
			throw new TimeoutException(message);
		}
	}
	
	@SuppressWarnings("serial")
	public static class NoSuchJGIFileException extends RuntimeException {
		
		public NoSuchJGIFileException(String msg) {
			super(msg);
		}
	}
	
	@SuppressWarnings("serial")
	public static class NoSuchJGIFileGroupException extends RuntimeException {
		
		public NoSuchJGIFileGroupException(String msg) {
			super(msg);
		}
	}
	
	@SuppressWarnings("serial")
	public static class TimeoutException extends RuntimeException {
		
		public TimeoutException(String msg) {
			super(msg);
		}
	}
}