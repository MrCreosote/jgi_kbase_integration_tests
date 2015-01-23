package us.kbase.jgiintegration.common;

import java.io.IOException;
import java.net.URL;

import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.Tuple2;
import us.kbase.wipedev03.WipeDev03Client;

public class JGIUtils {

	public static void wipeRemoteServer(URL server, String user, String pwd)
			throws IOException, JsonClientException {
		WipeDev03Client wipe = new WipeDev03Client(server, user, pwd);
		wipe.setIsInsecureHttpConnectionAllowed(true);
		wipe.setAllSSLCertificatesTrusted(true);
		wipe.setConnectionReadTimeOut(60000);
		System.out.print("triggering remote wipe of test data stores... ");
		Tuple2<Long, String> w = wipe.wipeDev03();
		if (w.getE1() > 0 ) {
			throw new WipeException(
					"Wipe of test server failed. The wipe server said:\n" +
							w.getE2());
		}
		System.out.println("done. Server said:\n" + w.getE2());
	}

	@SuppressWarnings("serial")
	public static class WipeException extends RuntimeException {
		
		public WipeException(String msg) {
			super(msg);
		}
	}
	
}