package us.kbase.jgiintegration.perftest;

import static us.kbase.jgiintegration.common.JGIUtils.wipeRemoteServer;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MassPushFiles {
	
	private static final boolean SKIP_WIPE = true;
	private static final String JGI_PUSHABLE_FILES = 
			"/home/crusherofheads/localgit/jgi_kbase_integration_tests/test_data/putative_pushable_files";
	
	private static final String WIPE_URL = 
			"http://dev03.berkeley.kbase.us:9000";
	
	private static final int WORKERS = 20;

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
					temp[0], temp[1], temp[2], temp[3]);
			if (index >= filesets.size()) {
				index = 0;
			}
			filesets.get(index).add(pf);
			index++;
		}
		for (List<PushableFile> list: filesets) {
			System.out.println(list.size());
		}
		
		
	}

}
