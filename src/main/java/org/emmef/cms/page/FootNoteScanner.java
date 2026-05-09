package org.emmef.cms.page;

import com.google.common.collect.ImmutableMap;
import lombok.Data;
import lombok.NonNull;
import org.jsoup.nodes.Element;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.emmef.cms.page.DocumentUtils.NOTE_ELEMENT;

@Data
class FootNoteScanner {
	private final PathResolver pathResolver;
	private final PathResolver.PageLink page;

	public Map<String, Note> scanForNotes(@NonNull Element sourceDocument, @NonNull Element article) {
		var allNotes = findAllNotesById(sourceDocument);
		var referenced = new LinkedHashMap<String, Element>();

		LinkedHashMap<String, Element> added = handleFootNotes(article, referenced, allNotes);
		while (!added.isEmpty()) {
			referenced.putAll(added);
			var copy = new HashMap<>(added);
			added.clear();
			copy.values().forEach(note -> added.putAll(handleFootNotes(note, referenced, allNotes)));
		}

		ImmutableMap.Builder<String, Note> builder = ImmutableMap.builder();
		AtomicInteger counter = new AtomicInteger();
		referenced.forEach((id, note) -> builder.put(id, new Note(note, counter.incrementAndGet())));
		return builder.build();
	}

	public void removeManagedNotes(@NonNull Element root) {
		root.getElementsByTag(NOTE_ELEMENT).forEach(note -> {
			String id = note.attr("id");
			if (id.isBlank()) {
				return;
			}
			note.remove();
		});
	}

	private static @NonNull Map<String, Element> findAllNotesById(Element sourceDocument) {
		var notes = new HashMap<String, Element>();
		sourceDocument.getElementsByTag(NOTE_ELEMENT).forEach(note -> {
			String id = note.attr("id");
			if (id.isBlank()) {
				return;
			}
			notes.put(id, note);
		});
		return notes;
	}

	private LinkedHashMap<String, Element> handleFootNotes(Element element, Map<String, Element> referenced, Map<String, Element> allNotes) {
		var result = new LinkedHashMap<String, Element>();
		PageUtils.scanForManagedAnchors(pathResolver, element, (anchor, link) -> {
			PathResolver.PageLink localized = page.localize(link);
			if (!localized.isLocal()) {
				return;
			}

			String id = localized.getLocalId();
			Element note = allNotes.get(id);
			if (note != null && !referenced.containsKey(id)) {
				result.putIfAbsent(id, note);
			}
		});
		return result;
	}

	public record Note(@NonNull Element node, @NonNull Integer number) {}
}
