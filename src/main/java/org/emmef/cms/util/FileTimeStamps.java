package org.emmef.cms.util;

import lombok.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.DateTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.function.Function;
import java.util.function.Supplier;

public class FileTimeStamps {

	public static @NonNull Supplier<DateTime> createdSupplier(@NonNull Path file) {
		return () -> created(file);
	}

	public static @NonNull Supplier<DateTime> modifiedSupplier(@NonNull Path file) {
		return () -> modified(file);
	}

	public static @Nullable DateTime created(@NonNull Path file) {
		return getTime(file, (a) -> a.creationTime());
	}

	public static @Nullable DateTime modified(@NonNull Path file) {
		return getTime(file, (a) -> a.creationTime());
	}

	private static DateTime getTime(@NonNull Path file, @NonNull Function<BasicFileAttributes, FileTime> getter) {
		BasicFileAttributes attributes = getAttributes(file);
		if (attributes == null) {
			return null;
		}
		FileTime fileTime = getter.apply(attributes);

		return fileTime != null ? new DateTime(fileTime.toMillis()) : null;
	}

	private static BasicFileAttributes getAttributes(@NonNull Path file) {
		try {
			return Files.readAttributes(file, BasicFileAttributes.class);
		} catch (IOException e) {
			return null;
		}
	}
}
