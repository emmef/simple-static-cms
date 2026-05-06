package org.emmef.cms.util;

import lombok.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class PathUtil {
	public static @NonNull Path realAndNormalized(@NonNull Path path) {
		try {
			return path.toRealPath().normalize();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static @NonNull Path realAndNormalized(@NonNull Path path, @NonNull BiConsumer<IOException, Path> onError) {
		try {
			return path.toRealPath().normalize();
		} catch (IOException e) {
			try {
				onError.accept(e, path);
			} catch (RuntimeException ex) {
				throw ex;
			}
			throw new RuntimeException(e);
		}
	}

	public static boolean withRealAndNormalized(@NonNull Path path, @NonNull Consumer<Path> consumer, @NonNull BiConsumer<IOException, Path> onError) {
		try {
			consumer.accept(path.toRealPath().normalize());
			return true;
		} catch (IOException e) {
			onError.accept(e, path);
		}
		return false;
	}
}
