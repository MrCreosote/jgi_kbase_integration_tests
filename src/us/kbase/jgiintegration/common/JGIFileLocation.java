package us.kbase.jgiintegration.common;

public class JGIFileLocation {
	private final String group;
	private final String file;
	private final boolean expectReject;
	
	public JGIFileLocation(String group, String file) {
		this(group, file, false);
	}
	
	/** The location of a file on a JGI genome portal page.
	 * @param group the file group containing the file
	 * @param file the name of the file
	 * @param expectRejection true if the file should be rejected for
	 * pushing to KBase.
	 */
	public JGIFileLocation(String group, String file,
			boolean expectRejection) {
		this.group = group;
		this.file = file;
		this.expectReject = expectRejection;
	}
	
	public String getGroup() {
		return group;
	}
	
	public String getFile() {
		return file;
	}
	
	public boolean isExpectedRejection() {
		return expectReject;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("JGIFileLocation [group=");
		builder.append(group);
		builder.append(", file=");
		builder.append(file);
		builder.append(", expectReject=");
		builder.append(expectReject);
		builder.append("]");
		return builder.toString();
	}
}
