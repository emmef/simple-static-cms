package org.emmef.cms.page;

import java.nio.file.Path;
import java.util.List;

public interface PathInfo {
	Path getPath();
	Path getRelativePath();
	String getAbsoluteUrl();
	boolean isIndex();
	boolean isRoot();
	public List<String> getMainTag();
	public Path getRootPath();
	public boolean isSame(String href);
}
