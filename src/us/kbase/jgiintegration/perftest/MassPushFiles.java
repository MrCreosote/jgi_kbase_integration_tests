package us.kbase.jgiintegration.perftest;

import static us.kbase.jgiintegration.common.JGIUtils.loadPushableFiles;
import static us.kbase.jgiintegration.common.JGIUtils.wipeRemoteServer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import us.kbase.jgiintegration.common.JGIFileLocation;
import us.kbase.jgiintegration.common.JGIOrganismPage;
import us.kbase.jgiintegration.common.PushableFile;

import com.gargoylesoftware.htmlunit.WebClient;

/** Currently undocumented and unused.
 * @author gaprice@lbl.gov
 *
 */
public class MassPushFiles {
	
	private static final boolean SKIP_WIPE = false;
	private static final String JGI_PUSHABLE_FILES = 
			"/home/crusherofheads/localgit/jgi_kbase_integration_tests/test_data/putative_pushable_files";
	private static final URL JGI_PORTAL_URL;
	static {
		try {
			JGI_PORTAL_URL = new URL("http://genomeportal.jgi.doe.gov");
//			JGI_PORTAL_URL = new URL("http://genome.jgi.doe.gov");
		} catch (MalformedURLException mue) {
			throw new RuntimeException("You big dummy", mue);
		}
	}
	
	private static final int WORKERS = 5;//20;
	private static final int MAX_PUSH_PER_WORKER = 10;

	private static final String WIPE_URL = 
			"http://dev03.berkeley.kbase.us:9000";

	private static String JGI_USER;
	private static String JGI_PWD;
	
	
	public static void main(String[] args) throws Exception {
		Logger.getLogger("com.gargoylesoftware").setLevel(Level.OFF);
		JGI_USER = System.getProperty("test.jgi.user");
		JGI_PWD = System.getProperty("test.jgi.pwd");
		
		String wipeUser = System.getProperty("test.kbase.wipe_user");
		String wipePwd = System.getProperty("test.kbase.wipe_pwd");
		if (!SKIP_WIPE) {
			wipeRemoteServer(new URL(WIPE_URL), wipeUser, wipePwd);
		}
		List<PushableFile> files = loadPushableFiles(JGI_PUSHABLE_FILES);
		
		List<List<PushableFile>> filesets =
				new LinkedList<List<PushableFile>>();
		for (int i = 0; i < WORKERS; i++) {
			filesets.add(new LinkedList<PushableFile>());
		}
		int index = 0;
		for (PushableFile pf: files) {
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
		
		Thread.sleep(3000); // let the stdout dump
		index = 1;
		int ttlpassed = 0;
		int ttlfailed = 0;
		List<Long> loadTimesNanos = new LinkedList<Long>();
		for (PushFilesToKBaseRunner runner: theruns) {
			System.out.println(String.format(
					"Worker %s results:", index,
					runner.getResults().size()));
			int passed = 0;
			loadTimesNanos.addAll(runner.getPageLoadTimesInNanos());
			for (Result res: runner.getResults()) {
				PushableFile f = res.file;
				String name;
				if (f == null) {
					name = " worker - pushing jobs failed";
				} else {
					name = f.getOrganism() + "/" + f.getFileGroup() + "/" +
							f.getFile();
				}
				if (res.exception == null) {
					System.out.println(String.format(
							"\tPushed %s at %s", name, res.timestamp));
					passed++;
				} else {
					System.out.println(String.format(
							"\tException for %s at %s", name, res.timestamp));
					res.exception.printStackTrace(System.out);
				}
			}
			System.out.println(String.format("\tPassed: %s, failed: %s",
					passed, runner.getResults().size() - passed));
			index++;
			ttlpassed += passed;
			ttlfailed += runner.getResults().size() - passed;
		}
		System.out.println(String.format(
				"Total passed: %s, total failed: %s",
				ttlpassed, ttlfailed));
		System.out.println("\nPage load times in seconds:");
		for (Long nanotime: loadTimesNanos) {
			System.out.println(nanotime / 1000000000.0);
		}
		
	}
	
	private static class Result {
		public PushableFile file;
		public Throwable exception;
		public Date timestamp;
		public Result(PushableFile file, Throwable exception) {
			super();
			this.file = file;
			this.exception = exception;
			this.timestamp = new Date();
		}
	}
	
	private static class PushFilesToKBaseRunner implements Runnable {
		
		private final List<PushableFile> files;
		private final List<Result> results =
				new LinkedList<Result>();
		private final List<Long> timeInNanos = new LinkedList<Long>();
		
		public PushFilesToKBaseRunner(List<PushableFile> files) {
			this.files = files;
		}
		
		@Override
		public void run() {
			WebClient wc = new WebClient();
			try {
				//perform known good login
				new JGIOrganismPage(JGI_PORTAL_URL,
						wc, "BlaspURHD0036", JGI_USER, JGI_PWD);
			} catch (Throwable e) {
				results.add(new Result(null, e));
				return;
			}
			int count = 1;
			for (PushableFile f: files) {
				if (count > MAX_PUSH_PER_WORKER) {
					break;
				}
				try {
					Long start = System.nanoTime();
					JGIOrganismPage p = new JGIOrganismPage(JGI_PORTAL_URL,
							wc, f.getOrganism(), null, null);
					timeInNanos.add(System.nanoTime() - start - 5000000000L);
					p.selectFile(new JGIFileLocation(
							f.getFileGroup(), f.getFile()));
					p.pushToKBase();
					results.add(new Result(f, null));
				} catch (Throwable e) {
					results.add(new Result(f, e));
				}
				count++;
			}
		}
		
		public List<Result> getResults() {
			return results;
		}
		
		public List<Long> getPageLoadTimesInNanos() {
			return timeInNanos;
		}
		
	}
}
