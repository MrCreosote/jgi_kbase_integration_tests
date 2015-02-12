package us.kbase.jgiintegration.common;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.List;

import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.Tuple2;
import us.kbase.wipedev03.WipeDev03Client;

public class JGIUtils {

	public static WipeDev03Client wipeRemoteServer(URL server, String user,
			String pwd)
			throws IOException, JsonClientException {
		WipeDev03Client wipe = new WipeDev03Client(server, user, pwd);
		wipe.setIsInsecureHttpConnectionAllowed(true);
		wipe.setAllSSLCertificatesTrusted(true);
		wipe.setConnectionReadTimeOut(120000);
		System.out.print("triggering remote wipe of test data stores... ");
		Tuple2<Long, String> w = wipe.wipeDev03();
		if (w.getE1() > 0 ) {
			throw new WipeException(
					"Wipe of test server failed. The wipe server said:\n" +
							w.getE2());
		}
		System.out.println("done. Server said:\n" + w.getE2());
		return wipe;
	}

	@SuppressWarnings("serial")
	public static class WipeException extends RuntimeException {
		
		public WipeException(String msg) {
			super(msg);
		}
	}
	
	public static List<PushableFile> loadPushableFiles(String file)
			throws IOException {
		List<String> lines = Files.readAllLines(
				new File(file).toPath(),
					Charset.forName("UTF-8"));
		
		List<PushableFile> files = new LinkedList<PushableFile>();
		for (String line: lines) {
			String[] temp = line.split("\t");
			files.add(new PushableFile(temp[1], temp[0], temp[2], temp[3]));
		}
		return files;
	}
	
}
