package us.kbase.jgiintegration.common;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import us.kbase.common.test.TestException;

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

/** This class represents a JGI organism page and allows performing
 * operations - primarily selecting files and pushing them to KBase - on that
 * page.
 * @author gaprice@lbl.gov
 *
 */
public class JGIOrganismPage {

	private final static String JGI_SIGN_ON =
			"https://signon.jgi.doe.gov/signon";
	
	private final static String JGI_ORG_PAGE_SUFFIX =
			"/pages/dynamicOrganismDownload.jsf?organism=";
	private final static URL JGI_ORG_PAGE_DEFAULT;
	static {
		try {
			JGI_ORG_PAGE_DEFAULT = new URL("http://genomeportal.jgi.doe.gov");
		} catch (MalformedURLException mue) {
			throw new RuntimeException("You big dummy", mue);
		}
	}
	
	private final String organismCode;
	private HtmlPage page = null;
	private final Set<JGIFileLocation> selected =
			new HashSet<JGIFileLocation>();

	/** Construct a new organism page using the default JGI portal url.
	 * @param client the client to use to connect to the page.
	 * @param organismCode the JGI organism code.
	 * @param JGIuser the username for the JGI user that will sign in to JGI.
	 * Set as null to skip login.
	 * @param JGIpwd the password for the JGI user.
	 * @throws Exception if an exception occurs.
	 */
	public JGIOrganismPage(
			WebClient client,
			String organismCode,
			String JGIuser,
			String JGIpwd)
			throws Exception {
		this(JGI_ORG_PAGE_DEFAULT, client, organismCode, JGIuser, JGIpwd);
	}
	
	/** Construct a new organism page.
	 * @param portalURL the URL of the JGI genome portal.
	 * @param client the client to use to connect to the page.
	 * @param organismCode the JGI organism code.
	 * @param JGIuser the username for the JGI user that will sign in to JGI.
	 * Set as null to skip login.
	 * @param JGIpwd the password for the JGI user.
	 * @throws Exception if an exception occurs.
	 */
	public JGIOrganismPage(
			URL portalURL,
			WebClient client,
			String organismCode,
			String JGIuser,
			String JGIpwd)
			throws Exception {
		super();
		//this makes weird things happen. Calls never finish, etc.
//		client.setAjaxController(new NicelyResynchronizingAjaxController());
		URI jgiOrgPage = portalURL.toURI().resolve(JGI_ORG_PAGE_SUFFIX);
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
		page = loadOrganismPage(jgiOrgPage, client, organismCode);
		checkPermissionOk();
		waitForPageToLoad();
		waitForJS();
		// try a longer wait time & check for any running js afterwards to fix
		// the intermittent file group not found errors. Hypothesis is that
		// the page partially loads but the file group toggle isn't set up
		// appropriately when it's clicked.
		Thread.sleep(5000);
		waitForJS();
		System.out.println(String.format(
				"Opened %s page at %s, %s characters.",
				organismCode, new Date(), page.asXml().length()));
		closePushedFilesDialog(false);
	}

	/** Waits for a WebClient's background JavaScript jobs to complete.
	 */
	private void waitForJS() {
		int jobs = 1;
		while (jobs > 0) {
			System.out.println("Waiting for background JS to complete at " +
					new Date());
			jobs = page.getWebClient()
					.waitForBackgroundJavaScript(5 * 60 * 1000); //5 min 
		}
		System.out.println(
				"No JS jobs scheduled for next 5 mins, calling it a day at " +
						new Date());
	}

	private void waitForPageToLoad()
			throws InterruptedException, TimeoutException {
		int timeoutSec = 60;
		waitForGlobusButtonLoad(timeoutSec, "Globus button");
		waitForXPathLoad(timeoutSec,
				"//input[contains(@class, 'pushToKbaseClass')]",
				"PtKB button");
		waitForXPathLoad(timeoutSec, "//div[@class='rich-tree-node-children']",
				"file tree");
	}
	
	private void waitForGlobusButtonLoad(int timeoutSec, String name)
			throws InterruptedException, TimeoutException {
		Long startNanos = System.nanoTime(); 
		while (!hasGlobusButton()) {
			Thread.sleep(1000);
			checkTimeout(startNanos, timeoutSec, String.format(
					"Timed out waiting for %s to load after %s seconds.",
					name, timeoutSec), "Page contents\n" + page.asXml());
			System.out.println("waiting on " + name +" load at " + new Date());
		}
	}

	private boolean hasGlobusButton() {
		/* This is totally stupid and I have no idea what's going on here,
		 * but traversing down the DOM results in a NPE at the div with
		 * a together class, and the div is empty even though printing the xml
		 * from the parent div shows its children. So here's a (worse) hack.
		 */
		List<DomElement> anch = page.getElementsByTagName("a");
		for (DomElement de: anch) {
			if (de.getTextContent().equals("Download via Globus")) {
				return true;
			}
		}
		return false;
//		DomElement fileTreePanel = page.getElementById(
//				"downloadForm:fileTreePanel");
//		DomNode globusbutton = null;
//		try {
//			globusbutton = fileTreePanel
//					.getFirstChild() //rich_panel no_frame
//					.getFirstChild() //rich-panel-body
//					.getFirstChild() //together
//					.getFirstChild(); //button
//		} catch (NullPointerException npe) {
//			// not here yet
//		}
//		return globusbutton;
	}

	private void waitForXPathLoad(int timeoutSec, String xpath, String name)
			throws InterruptedException, TimeoutException {
		List<HtmlElement> elements = getElementsByXPath(xpath);
		Long startNanos = System.nanoTime(); 
		while (elements.isEmpty()) {
			Thread.sleep(1000);
			checkTimeout(startNanos, timeoutSec, String.format(
					"Timed out waiting for %s to load after %s seconds.",
					name, timeoutSec), "Page contents\n" + page.asXml());
			elements = getElementsByXPath(xpath);
			System.out.println("waiting on " + name +" load at " + new Date());
		}
//		printXPathElements(xpath, name, elements);
	}

	@SuppressWarnings("unused")
	private void printXPathElements(String xpath, String name,
			List<HtmlElement> elements) {
		System.out.println("----- " + xpath + ", name: " + name + " ------");
		int count = 1;
		for (Object e: elements) {
			System.out.println("--- " + count + " ---");
			count++;
			if (e instanceof HtmlElement) {
				System.out.println(((HtmlElement)e).asXml());
			} else {
				System.out.println(e);
			}
		}
		System.out.println("--- printed " + (count - 1) + " ---");
	}

	private List<HtmlElement> getElementsByXPath(String xpath) {
		List<?> elements = page.getByXPath(xpath);
		List<HtmlElement> ret = new LinkedList<HtmlElement>();
		for (Object e: elements) {
			HtmlElement el = (HtmlElement) e;
			if (el.isDisplayed()) {
				ret.add(el);
			}
		}
		return ret;
	}

	private void checkPermissionOk() throws JGIPermissionsException {
		List<?> filetree = page.getByXPath("//div[@class='warning']");
		if (!filetree.isEmpty()) {
			DomElement e = (DomElement) filetree.get(0);
			if (e.getTextContent().contains("you do not have permission")) {
				throw new JGIPermissionsException(
						"No permission for organism " + organismCode);
			}
		}
		
	}

	private HtmlPage loadOrganismPage(URI jgiOrgPage, WebClient client,
			String organismCode)
			throws Exception {
		//This exception does not affect user experience or functionality
		//at all and occurs rather frequently, so we ignore it
		String acceptableExceptionContents =
				"https://issues.jgi-psf.org/rest/collectors/1.0/configuration/trigger/4c7588ab?os_authType=none&callback=trigger_4c7588ab";
		HtmlPage page = null;
		while (page == null) {
			try {
				page = client.getPage(jgiOrgPage + organismCode);
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
		assertThat("1 form on login page", forms.size(), is(1));
		HtmlForm form = forms.get(0);
		form.getInputByName("login").setValueAttribute(user);
		form.getInputByName("password").setValueAttribute(password);
		HtmlPage loggedIn = form.getInputByName("commit").click();
		HtmlDivision div = loggedIn.getHtmlElementById("highlight-me");
		assertThat("signed in correctly", div.getTextContent().trim(),
				is("You have signed in successfully."));
	}
	
	/** Returns the url for an organism page.
	 * @param portalURL the url of the JGI genome portal.
	 * @param organism the JGI organism code.
	 * @return the URL of the page for the organism.
	 */
	public static String getURLforOrganism(URL portalURL, String organism) {
		return portalURL + JGI_ORG_PAGE_SUFFIX + organism;
	}
	
	/** Returns the organism code for this page.
	 * @return the organism code for this page.
	 */
	public String getOrganismCode() {
		return organismCode;
	}
	
	/** Prints the contents of this web page as xml to standard out.
	 * 
	 */
	public void printPageToStdout() {
		System.out.println(page.asXml());
	}
	
	/** Get the names of the first level filegroups in this page.
	 * @return the names of the first level filegroups.
	 */
	public List<String> listFileGroups() {
		List<?> filetree = page.getByXPath("//div[@class='rich-tree ']");
		if (filetree.isEmpty()) {
			System.out.println("No rich tree found in page. Current page:\n" +
					page.asXml());
			throw new TestException("No rich tree found in page");
			
		}
		DomElement ft = (DomElement) filetree.get(0);
		DomElement fileContainer = (DomElement) ft
				.getFirstChild()
				.getChildNodes().get(2);
		List<String> ret = new LinkedList<String>();
		for (DomNode child: fileContainer.getChildNodes()) {
			DomElement echild = (DomElement) child;
			if (echild.getTagName().equals("table")) {
				DomNodeList<HtmlElement> group =
						echild.getElementsByTagName("b");
				for (HtmlElement e: group) {
					ret.add(e.getTextContent());
				}
			}
		}
		return ret;
	}
	
	/** List the files in a file group. This function only works for top
	 * level filegroups.
	 * @param fileGroup the name of the file group.
	 * @return the list of files in the file group.
	 * @throws IOException if an IO exception occurs.
	 * @throws InterruptedException if this function is interrupted while
	 * sleeping.
	 * @throws TimeoutException if a timeout occurs
	 */
	public List<String> listFiles(String fileGroup) 
			throws IOException, InterruptedException, TimeoutException {
		DomElement fg = openFileGroup(fileGroup);
		List<HtmlElement> names = fg.getElementsByTagName("b");
		List<String> ret = new LinkedList<String>();
		for (HtmlElement he: names) {
			ret.add(he.getTextContent());
		}
		return ret;
	}
	
	/** Select a file on the organism page (e.g. check the checkbox).
	 * @param file the file to select.
	 * @throws IOException if an IO exception occurs.
	 * @throws InterruptedException if this function is interrupted while
	 * sleeping.
	 * @throws TimeoutException if a timeout occurs
	 */
	public void selectFile(JGIFileLocation file)
			throws IOException, InterruptedException, TimeoutException {
		selectFile(file, true);
	}
	
	/** Select or unselect a file on the organism page (e.g. check or uncheck
	 * the checkbox).
	 * @param file the file to select or unselect.
	 * @param select true to select the file, false to unselect.
	 * @throws IOException if an IO exception occurs.
	 * @throws InterruptedException if this function is interrupted while
	 * sleeping.
	 * @throws TimeoutException if a timeout occurs
	 */
	public void selectFile(JGIFileLocation file, boolean select)
			throws IOException, InterruptedException, TimeoutException {
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
		
		if (select == filetoggle.isChecked()) {
			return;
		}
		this.page = filetoggle.click();
		if (select) {
			selected.add(file);
		} else {
			selected.remove(file);
		}
		waitForJS();
		Thread.sleep(1000); //every click gets sent to the server
		System.out.println(String.format("%sed file %s from group %s.",
				selstr, file.getFile(), file.getGroup()));
	}
	
	private DomElement findFile(JGIFileLocation file)
			throws IOException, InterruptedException, TimeoutException {
		DomElement fileGroup = openFileGroup(file.getGroup());
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

	private DomElement openFileGroup(String group)
			throws IOException, InterruptedException, TimeoutException {
		int timeoutSec = 60;
		System.out.println(String.format("Opening file group %s at %s... ",
				group, new Date()));
		
		DomElement fileGroupText = findFileGroup(group);
		DomElement fileContainer = getFilesDivFromFilesGroup(
				fileGroupText);
		
		if (fileContainer.isDisplayed()) {
			System.out.println(String.format("File group %s already open.",
					group));
			return fileContainer;
		}
		fileContainer = openClosedFileGroup(group, timeoutSec);
		System.out.println(String.format("Opened file group %s at %s.",
				group, new Date()));
		return fileContainer;
	}

	private DomElement openClosedFileGroup(String group, int timeoutSec)
			throws IOException, InterruptedException, TimeoutException {
		DomElement fileGroupText = findFileGroup(group);
		DomElement fileContainer = getFilesDivFromFilesGroup(
				fileGroupText);
		
		final HtmlAnchor fileSetToggle = (HtmlAnchor) fileGroupText
				.getParentNode() //td
				.getPreviousSibling() //td folder icon
				.getPreviousSibling() //td toggle icon
				.getChildNodes().get(0) //div
				.getChildNodes().get(0); //a
		
		final String toggleDOM = fileSetToggle.asXml();
		
		this.page = fileSetToggle.click();
		waitForJS();
		Thread.sleep(1000); // wait for file group to open, requires a server call
		
		Long startNanos = System.nanoTime(); 
		while (!fileContainer.isDisplayed()) {
//			System.out.println("------------file group text--------------");
//			System.out.println(fileGroupText.asXml());
//			System.out.println("------------file container text--------------");
//			System.out.println(fileContainer.asXml());
//			System.out.println("----------------------");
			fileGroupText = findFileGroup(group, toggleDOM);
			fileContainer = getFilesDivFromFilesGroup(fileGroupText);
			checkTimeout(startNanos, timeoutSec, String.format(
					"Timed out waiting for file group %s to open after %s seconds, contents:\n%s",
					group, timeoutSec, fileContainer.asXml()));
			Thread.sleep(1000);
		}
		return fileContainer;
	}

	/** Push the selected files to KBase.
	 * @throws IOException if an IO exception occurs.
	 * @throws InterruptedException if this function is interrupted while
	 * sleeping.
	 * @throws TimeoutException if a timeout occurs
	 * @throws PushException if the push fails
	 */
	public void pushToKBase()
			throws IOException, InterruptedException, TimeoutException,
				PushException {
		System.out.println(String.format("Pushing files to KBase at %s...",
				new Date()));

		List<?> pushlist =  page.getByXPath(
				"//input[contains(@class, 'pushToKbaseClass')]");
		assertThat("only 1 pushToKbaseClass", pushlist.size(), is(1));
		
		HtmlInput push = (HtmlInput) pushlist.get(0);
		
		this.page = push.click();
		// do not wait for JS here, hangs forever for some reason
//		waitForJS();

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
		HtmlElement modalFooter = getElementsByXPath(
				"//div[@class='modal-footer']").get(0);
		HtmlElement ok = (HtmlElement) modalFooter.getFirstChild();
//		HtmlInput ok = (HtmlInput) resDialogDiv
//				.getFirstChild() //tbody
//				.getFirstChild() //tr
//				.getFirstChild() //td
//				.getFirstChild() //div
//				.getChildNodes().get(2) //div
//				.getFirstChild(); //input

		page = ok.click();
		Thread.sleep(2000);
		
		resDialogDiv = (HtmlElement) page.getElementById(
						"downloadForm:showFilesPushedToKbaseContentTable");
		assertThat("Dialog closed", resDialogDiv.isDisplayed(), is(false));
	}

	/** Get the workspace name associated with this organism page.
	 * @param user the KBase username of the user that will push the files.
	 * @return
	 */
	public String getWorkspaceName(String user) {
		List<?> orgNames = page.getByXPath("//div[@class='organismName']");
		assertThat("only 1 organismName class", orgNames.size(), is(1));
		DomNode orgName = (DomNode) orgNames.get(0);
		return orgName.getTextContent()
				.replace(" ", "_")
				.replace("-", "")
				.replace(".", "")
				.replace("/", "")
				+ '_' + user;
	}

	private void checkPushedFiles()
			throws InterruptedException, TimeoutException, PushException {
		// make this configurable per test?
		//may need to be longer for tape files
		waitForPtKBResult();
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

	private void waitForPtKBResult()
			throws TimeoutException, InterruptedException, PushException {
		waitForPtKBDialog();
		
		/* this should happen almost immediately after the model shows up
		 * the JGI JS code sets the modal visible and then fills in the
		 * contents in the same fn
		 */
		int timeoutSec = 10;
		HtmlElement accFilesDiv =
				(HtmlElement) page.getElementById("acceptedKbaseFiles");
		HtmlElement rejFilesDev =
				(HtmlElement) page.getElementById("rejectedKbaseFiles");
		HtmlElement errDiv =
				(HtmlElement) page.getElementById("foundKbaseErrors");
		Long startNanos = System.nanoTime();
		while (!accFilesDiv.isDisplayed() && !rejFilesDev.isDisplayed() &&
				!errDiv.isDisplayed()) {
			checkTimeout(startNanos, timeoutSec, String.format(
					"Timed out waiting for PtKB result dialog fill after %s seconds",
					timeoutSec), "Dialog contents:\n" +
					getKBaseResultDialog().asXml());
			Thread.sleep(1000);
		}
		Thread.sleep(1000); // the error div is hidden last, *after* the modal
		// is displayed, so wait a sec to be sure it's hidden
		if (errDiv.isDisplayed()) {
			System.out.println("PtKB returned with error. Dialog contents:");
			System.out.println(getKBaseResultDialog().asXml());
			throw new PushException("PtKB returned with error. Error div contents:\n" +
					errDiv.asXml());
		}
	}

	private void waitForPtKBDialog()
			throws TimeoutException, InterruptedException {
		int timeoutSec = 60;
		
		DomNode modal = getKBaseResultDialog();
		Long startNanos = System.nanoTime();
		while (!modal.isDisplayed()) {
			checkTimeout(startNanos, timeoutSec, String.format(
					"Timed out waiting for PtKB result dialog after %s seconds",
					timeoutSec), "Dialog contents:\n" +
					getKBaseResultDialog().asXml());
			Thread.sleep(1000);
		}
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

	private Set<String> getPushedFileList(String elementID) {
		HtmlElement resDialogDiv =
				(HtmlElement) page.getElementById(elementID);
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
		return resDialogDiv.getParentNode(); //div modal-body
	}

	private DomElement getFilesDivFromFilesGroup(DomElement selGroup) {
		return (DomElement) selGroup
				.getParentNode() //td
				.getParentNode() //tr
				.getParentNode() //tbody
				.getParentNode() //table
				.getNextSibling(); //div below table
	}

	private DomElement findFileGroup(String group) {
		return findFileGroup(group, null);
	}
	
	private DomElement findFileGroup(String group, String toggleDOM) {
		//this is ugly but it doesn't seem like there's another way
		//to get the node
		DomElement selGroup = null;
		List<DomElement> bold = page.getElementsByTagName("b");
		for (DomElement de: bold) {
			if (group.equals(de.getTextContent())) {
				selGroup = de;
				break;
			}
		}
		if (selGroup == null) {
			System.out.println(String.format(
					"There is no file group %s for the organism %s. Found %s bold tags at %s:",
					group, organismCode, bold.size(), new Date()));
			for (DomElement de: bold) {
				System.out.println(de.asXml());
			}
			if (toggleDOM != null) {
				System.out.println(
						"DOM tree under toggle button prior to click:");
				System.out.println(toggleDOM);
			}
			System.out.println("Current URL: " + page.getUrl());
			System.out.println("Current page:");
			System.out.println(page.asXml());
			throw new NoSuchJGIFileGroupException(String.format(
					"There is no file group %s for the organism %s at %s",
					group, organismCode, new Date()));
		}
		return selGroup;
	}
	
	private static void checkTimeout(Long startNanos, int timeoutSec,
			String message) throws TimeoutException {
		checkTimeout(startNanos, timeoutSec, message, null);
	}
	
	private static void checkTimeout(Long startNanos, int timeoutSec,
			String message, String printToStdOut)  //ugh
			throws TimeoutException {
		if ((System.nanoTime() - startNanos) / 1000000000 > timeoutSec) {
			System.out.println(message);
			if (printToStdOut != null) {
				System.out.println(printToStdOut);
			}
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
	public static class TimeoutException extends Exception {
		
		public TimeoutException(String msg) {
			super(msg);
		}
		
		public TimeoutException(String msg, Throwable cause) {
			super(msg, cause);
		}
	}
	
	@SuppressWarnings("serial")
	public class JGIPermissionsException extends Exception {
		
		public JGIPermissionsException(String msg) {
			super(msg);
		}
	}
	
	@SuppressWarnings("serial")
	public class PushException extends Exception {
		
		public PushException(String msg) {
			super(msg);
		}
	}
}
