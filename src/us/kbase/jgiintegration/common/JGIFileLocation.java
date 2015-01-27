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
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (expectReject ? 1231 : 1237);
		result = prime * result + ((file == null) ? 0 : file.hashCode());
		result = prime * result + ((group == null) ? 0 : group.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		JGIFileLocation other = (JGIFileLocation) obj;
		if (expectReject != other.expectReject)
			return false;
		if (file == null) {
			if (other.file != null)
				return false;
		} else if (!file.equals(other.file))
			return false;
		if (group == null) {
			if (other.group != null)
				return false;
		} else if (!group.equals(other.group))
			return false;
		return true;
	}
}
