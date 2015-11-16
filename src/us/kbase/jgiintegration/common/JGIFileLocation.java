package us.kbase.jgiintegration.common;

/** Represents a file on a JGI genome portal page.
 * @author gaprice@lbl.gov
 *
 */
public class JGIFileLocation {
	private final String group;
	private final String file;
	private final boolean expectReject;
	
	/** The location of a file on a JGI genome portal page.
	 * @param group the file group containing the file
	 * @param file the name of the file
	 */
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
	
	/** Returns the file group.
	 * @return the file group.
	 */
	public String getGroup() {
		return group;
	}
	
	/** Returns the file name.
	 * @return the file name.
	 */
	public String getFile() {
		return file;
	}
	
	/** Returns whether, if pushed to KBase, the file is expected to be
	 * rejected for the push by the JGI front end.
	 * @return true if expected to be rejected.
	 */
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
