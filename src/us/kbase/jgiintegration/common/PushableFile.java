package us.kbase.jgiintegration.common;

/** Currently undocumented and unused.
 * @author gaprice@lbl.gov
 *
 */
public class PushableFile {
	
	private final String organism;
	private final String workspace;
	private final String fileGroup;
	private final String file;
	
	public PushableFile(String organism, String workspace, String fileGroup,
			String file) {
		super();
		this.organism = organism;
		this.workspace = workspace;
		this.file = file;
		this.fileGroup = fileGroup;
	}
	
	public String getFileGroup() {
		return fileGroup;
	}

	public String getOrganism() {
		return organism;
	}

	public String getWorkspace() {
		return workspace;
	}

	public String getFile() {
		return file;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("PushableFile [organism=");
		builder.append(organism);
		builder.append(", workspace=");
		builder.append(workspace);
		builder.append(", fileGroup=");
		builder.append(fileGroup);
		builder.append(", file=");
		builder.append(file);
		builder.append("]");
		return builder.toString();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((file == null) ? 0 : file.hashCode());
		result = prime * result
				+ ((fileGroup == null) ? 0 : fileGroup.hashCode());
		result = prime * result
				+ ((organism == null) ? 0 : organism.hashCode());
		result = prime * result
				+ ((workspace == null) ? 0 : workspace.hashCode());
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
		PushableFile other = (PushableFile) obj;
		if (file == null) {
			if (other.file != null)
				return false;
		} else if (!file.equals(other.file))
			return false;
		if (fileGroup == null) {
			if (other.fileGroup != null)
				return false;
		} else if (!fileGroup.equals(other.fileGroup))
			return false;
		if (organism == null) {
			if (other.organism != null)
				return false;
		} else if (!organism.equals(other.organism))
			return false;
		if (workspace == null) {
			if (other.workspace != null)
				return false;
		} else if (!workspace.equals(other.workspace))
			return false;
		return true;
	}
}