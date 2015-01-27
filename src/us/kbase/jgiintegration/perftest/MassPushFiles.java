package us.kbase.jgiintegration.perftest;

import static us.kbase.jgiintegration.common.JGIUtils.wipeRemoteServer;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import us.kbase.jgiintegration.common.JGIFileLocation;
import us.kbase.jgiintegration.common.JGIOrganismPage;

import com.gargoylesoftware.htmlunit.WebClient;

public class MassPushFiles {
	
	private static final boolean SKIP_WIPE = true;
	private static final String JGI_PUSHABLE_FILES = 
			"/home/crusherofheads/localgit/jgi_kbase_integration_tests/test_data/putative_pushable_files";
	
	private static final int WORKERS = 3;//20;
	private static final int MAX_PUSH_PER_WORKER = 3;

	private static final String WIPE_URL = 
			"http://dev03.berkeley.kbase.us:9000";

	private static String JGI_USER;
	private static String JGI_PWD;
	private static String KB_USER;
	private static String KB_PWD;
	
	
	public static void main(String[] args) throws Exception {
		Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);
		JGI_USER = System.getProperty("test.jgi.user");
		JGI_PWD = System.getProperty("test.jgi.pwd");
		KB_USER = System.getProperty("test.kbase.user1");
		KB_PWD = System.getProperty("test.kbase.pwd1");
		
		String wipeUser = System.getProperty("test.kbase.wipe_user");
		String wipePwd = System.getProperty("test.kbase.wipe_pwd");
		if (!SKIP_WIPE) {
			wipeRemoteServer(new URL(WIPE_URL), wipeUser, wipePwd);
		}
		List<String> lines = Files.readAllLines(
				new File(JGI_PUSHABLE_FILES).toPath(),
					Charset.forName("UTF-8"));
		
		List<List<PushableFile>> filesets =
				new LinkedList<List<PushableFile>>();
		for (int i = 0; i < WORKERS; i++) {
			filesets.add(new LinkedList<PushableFile>());
		}
		int index = 0;
		for (String line: lines) {
			String[] temp = line.split("\t");
			PushableFile pf = new PushableFile(
					temp[1], temp[0], temp[2], temp[3]);
			if (index >= filesets.size()) {
				index = 0;
			}
			filesets.get(index).add(pf);
			index++;
		}

		List<PushFilesToKBaseRunner> theruns =
				new LinkedList<PushFilesToKBaseRunner>();
		for (List<PushableFile> list: filesets) {
			theruns.add(new PushFilesToKBaseRunner(list));
		}
		List<Thread> threads = new LinkedList<Thread>();
		for (PushFilesToKBaseRunner r: theruns) {
			Thread t = new Thread(r);
			t.start();
			threads.add(t);
		}
		
		for (Thread t: threads) {
			t.join();
		}
		
		index = 1;
		int ttlpassed = 0;
		int ttlfailed = 0;
		for (PushFilesToKBaseRunner r: theruns) {
			System.out.println(String.format(
					"Worker %s results:", index,
					r.getResults().size()));
			int passed = 0;
			for (Entry<PushableFile, Throwable> e: r.getResults().entrySet()) {
				PushableFile f = e.getKey();
				String name = f.getOrganism() + "/" + f.getFileGroup() + "/" +
						f.getFile();
				if (e.getValue() == null) {
					System.out.println("\tPushed " + name);
					passed++;
				} else {
					System.out.println("\tException for " + name);
					e.getValue().printStackTrace(System.out);
				}
			}
			System.out.println(String.format("\tPassed: %s, failed: %s",
					passed, r.getResults().size() - passed));
			index++;
			ttlpassed += passed;
			ttlfailed += r.getResults().size() - passed;
		}
		System.out.println(String.format(
				"Total passed: %s, total failed: %s",
				ttlpassed, ttlfailed));
		
	}
	
	private static class PushFilesToKBaseRunner implements Runnable {
		
		private final List<PushableFile> files;
		private final Map<PushableFile, Throwable> results =
				new HashMap<PushableFile, Throwable>();
		public PushFilesToKBaseRunner(List<PushableFile> files) {
			this.files = files;
		}
		
		@Override
		public void run() {
			WebClient wc = new WebClient();
			try {
				//perform known good login
				new JGIOrganismPage(
						wc, "BlaspURHD0036", JGI_USER, JGI_PWD);
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}
			int count = 1;
			for (PushableFile f: files) {
				if (count > MAX_PUSH_PER_WORKER) {
					break;
				}
				results.put(f, null);
				try {
					JGIOrganismPage p = new JGIOrganismPage(
							wc, f.getOrganism(), null, null);
					p.selectFile(new JGIFileLocation(
							f.getFileGroup(), f.getFile()));
					p.pushToKBase(KB_USER, KB_PWD);
				} catch (Exception e) {
					results.put(f, e);
				}
				count++;
			}
		}
		
		public Map<PushableFile, Throwable> getResults() {
			return results;
		}
		
	}
}
