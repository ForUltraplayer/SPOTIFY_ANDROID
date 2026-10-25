// @file HangulReadingEngineTest.java
// @author: ForUltraplayer
// @brief Regression checks for original-song readings, host detection and cover timing.
package com.music.spotui.data.api;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HangulReadingEngineTest {
    private final HangulReadingEngine engine = new HangulReadingEngine();
    private static final List<String> LINES = Arrays.asList("青い空", "君の声", "夢の中", "明日の朝", "夜の風");
    private static final String HTML = "<title>fixture</title><div>青い空<br>아오이 소라<br>푸른 하늘</div>"
            + "<div>君の声<br>키미노 코에<br>너의 목소리</div>"
            + "<div>夢の中<br>유메노 나카<br>꿈속에서</div>"
            + "<div>明日の朝<br>아시타노 아사<br>내일 아침</div>"
            + "<div>夜の風<br>요루노 카제<br>밤의 바람</div>";

    @Test public void removesFeaturedArtistsFromTitleCandidates() {
        assertTrue(HangulReadingEngine.titles("モニタリング (feat. 初音ミク)", "DECO*27").contains("モニタリング"));
    }

    @Test public void ignoresInlineAndSeparateRepeatAnnotations() {
        String source = HTML.replace("青い空<br>", "青い空<span>X16</span><br>").replace("키미노 코에<br>", "키미노 코에<br>X19<br>");
        assertEquals(5, engine.match(LINES, HangulReadingEngine.parse(source), "fixture").matched);
        assertEquals(HangulReadingEngine.normalize("だって"), HangulReadingEngine.normalize("だだだって"));
    }

    @Test public void parsesHaneumDirectParagraphTriples() {
        String source = "<div class='border-b'><p>아오이 소라</p><p>青い空</p><p>푸른 하늘</p></div>";
        List<HangulReadingEngine.Pair> pairs = engine.pairs(HangulReadingEngine.parse(source));
        assertEquals(1, pairs.size()); assertEquals("아오이 소라", pairs.get(0).reading);
    }

    @Test public void findsHaneumSearchAndValidatesItsOriginalLyrics() throws Exception {
        HangulReadingEngine.Result result = engine.resolve("Fixture", "Artist", LINES, "", url -> {
            if (url.startsWith("https://haneum.site/songs?")) return "<a href='/songs/fixture'>song</a>";
            if (url.equals("https://haneum.site/songs/fixture")) return HTML;
            throw new IOException("missing");
        });
        assertTrue(result.valid()); assertEquals("https://haneum.site/songs/fixture", result.source);
    }

    @Test public void retriesOriginalTitleAsContentForTranslatedDocumentNames() throws Exception {
        HangulReadingEngine.Result result = engine.resolve("Execution Clap", "Artist", LINES, "", url -> {
            if (url.contains("target=content") && url.contains("Execution%20Clap")) return "<a href='/w/처형박수'>song</a>";
            if (url.equals("https://namu.wiki/w/처형박수")) return HTML;
            throw new IOException("missing");
        });
        assertTrue(result.valid()); assertTrue(result.source.endsWith("처형박수"));
    }

    @Test public void followsTranslatedDisambiguationPageTitle() throws Exception {
        HangulReadingEngine.Result result = engine.resolve("JapaneseTitle", "Artist", LINES, "", url -> {
            if (url.endsWith("JapaneseTitle")) return "<title>한글곡 - 나무위키</title><a href='/w/한글곡(VOCALOID)'>song</a>";
            if (url.endsWith("한글곡(VOCALOID)")) return HTML;
            throw new IOException("missing");
        });
        assertTrue(result.valid()); assertTrue(result.source.endsWith("한글곡(VOCALOID)"));
    }

    @Test public void revalidatesLearnedAliasBeforeReturningIt() throws Exception {
        final int[] calls = {0};
        HangulReadingEngine.Result result = engine.resolve("Fixture", "Artist", LINES, "", "https://namu.wiki/w/Alias", url -> {
            calls[0]++; return HTML;
        });
        assertTrue(result.valid()); assertEquals(1, calls[0]);
        assertEquals("https://namu.wiki/w/Alias", result.source);
        result = engine.resolve("Fixture", "Artist", LINES, "", "https://namu.wiki/w/Wrong", url -> {
            if (url.endsWith("Wrong")) return "<p>別の曲</p>";
            if (url.endsWith("Fixture")) return HTML;
            throw new IOException("missing");
        });
        assertEquals("https://namu.wiki/w/Fixture", result.source);
    }

    @Test public void automaticCompletionPreservesAuthoredRowsAndEnglish() {
        List<String> lines = engine.completeAutomatically(Arrays.asList("青い空", "君の声", "Hello world"),
                Arrays.asList("기존 발음", HangulReadingEngine.MISSING, "Hello world"));
        assertEquals("기존 발음", lines.get(0)); assertEquals("Hello world", lines.get(2));
        assertTrue(lines.get(1).matches(".*[가-힣].*")); assertEquals(3, lines.size());
        assertEquals("각코우", HangulReadingEngine.kanaToHangul("ガッコウ"));
        assertEquals("수우파아", HangulReadingEngine.kanaToHangul("スーパー"));
    }

    @Test public void cancelledLookupDoesNotReturnAnOtherwiseValidDocument() throws Exception {
        try {
            engine.resolve("Fixture", "Artist", LINES, "https://namu.wiki/w/Fixture", url -> {
                Thread.currentThread().interrupt(); return HTML;
            });
            fail("Cancellation must discard the result");
        } catch (IOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void rejectsHaneumApiAndInsecureOrPrivateAddresses() throws Exception {
        assertEquals("https://haneum.site/songs/123", HangulReadingEngine.checkedUrl("https://haneum.site/songs/123"));
        for (String address : Arrays.asList("https://haneum.site/api/users", "http://namu.wiki/w/Song", "https://127.0.0.1/songs/1", "https://namu.wiki:444/w/Song")) {
            try { HangulReadingEngine.checkedUrl(address); fail(address); } catch (IOException expected) { }
        }
    }

    @Test public void retainsExistingAuthorReadings() {
        HangulReadingEngine.Result result = engine.match(LINES, HangulReadingEngine.parse(HTML), "fixture");
        assertTrue(result.valid()); assertEquals(5, result.matched);
        assertEquals("아오이 소라", result.readings.get(0));
        assertEquals("키미노 코에", result.readings.get(1));
    }

    @Test public void rejectsMeaningOnlySource() {
        String fake = "<div>青い空<br>푸른 하늘<br>푸른 하늘</div>";
        assertTrue(engine.pairs(HangulReadingEngine.parse(fake)).isEmpty());
    }

    @Test public void excludesFootnotesAndFurigana() {
        HangulReadingEngine.Page page = HangulReadingEngine.parse(
                "<p>青<ruby>空<rt>そら</rt></ruby><a href='#fn-1'>99</a><sup>note</sup><br>아오조라<br>푸른 하늘</p><script>bad</script>");
        assertEquals("青空", page.lines.get(0));
        assertFalse(page.lines.toString().contains("bad"));
    }

    @Test public void rejectsDifferentSong() {
        assertFalse(engine.match(Arrays.asList("春の雨", "海の色", "星の音", "帰る道", "遠い山"),
                HangulReadingEngine.parse(HTML), "fixture").valid());
    }

    @Test public void retainsEnglishHookAndMissingLine() {
        List<String> source = Arrays.asList("青い空", "君の声", "夢の中", "明日の朝", "夜の風", "白い雪", "Hello world");
        HangulReadingEngine.Result result = engine.match(source, HangulReadingEngine.parse(HTML), "fixture");
        assertFalse(result.valid()); // 5 / 6 is below 85%.
        assertEquals(HangulReadingEngine.MISSING, result.readings.get(5));
        assertEquals("Hello world", result.readings.get(6));
    }

    @Test public void splitSentenceDoesNotInventHangulBoundaries() {
        List<HangulReadingEngine.Pair> pairs = Arrays.asList(
                new HangulReadingEngine.Pair("青い空に君の声が響く", "아오이 소라니 키미노 코에가 히비쿠"),
                new HangulReadingEngine.Pair("夢の中", "유메노 나카"),
                new HangulReadingEngine.Pair("明日の朝", "아시타노 아사"),
                new HangulReadingEngine.Pair("夜の風", "요루노 카제"));
        HangulReadingEngine.Result result = engine.align(Arrays.asList("青い空に", "君の声が響く", "夢の中", "明日の朝", "夜の風"), pairs, "fixture", false);
        assertTrue(result.valid());
        assertEquals(result.readings.get(0), result.readings.get(1));
        assertFalse(result.readings.get(0).contains("문장 전체"));
    }

    @Test public void mergesSourceRowsAndPreservesOrder() {
        HangulReadingEngine.Result result = engine.match(Arrays.asList("青い空 君の声", "夢の中", "明日の朝", "夜の風"),
                HangulReadingEngine.parse(HTML), "fixture");
        assertTrue(result.valid()); assertEquals("아오이 소라 키미노 코에", result.readings.get(0));
    }

    @Test public void acceptsKanaSpellingWithoutGeneratingReadings() {
        HangulReadingEngine.Result result = engine.match(Arrays.asList("あおいそら", "きみのこえ", "ゆめのなか", "あしたのあさ", "よるのかぜ"),
                HangulReadingEngine.parse(HTML), "fixture");
        assertTrue(result.valid()); assertEquals("아시타노 아사", result.readings.get(3));
    }

    @Test public void resolvesOriginalDespiteCoverArtist() throws Exception {
        List<String> visited = new java.util.ArrayList<>();
        HangulReadingEngine.Result result = engine.resolve("Lemon (Cover by Other Artist)", "Other Artist", LINES, "", url -> {
            visited.add(url); return HTML;
        });
        assertTrue(result.valid()); assertTrue(visited.get(0).endsWith("/Lemon"));
    }

    @Test public void followsDisambiguationSongLink() throws Exception {
        HangulReadingEngine.Result result = engine.resolve("unravel", "Ado", LINES, "", url ->
                url.endsWith("/unravel") ? "<a href='/w/unravel(%EB%85%B8%EB%9E%98)'>song</a>" : HTML);
        assertTrue(result.valid()); assertTrue(result.source.contains("unravel("));
    }

    @Test public void validatesEverySourceAndRedirectTarget() throws Exception {
        assertEquals("https://namu.wiki/w/Lemon", HangulReadingEngine.checkedUrl("https://namu.wiki/w/Lemon#x"));
        for (String url : Arrays.asList("http://namu.wiki/w/x", "https://localhost/", "https://namu.wiki.evil.test/", "https://u:p@namu.wiki/w/x", "https://namu.wiki:444/w/x")) {
            try { HangulReadingEngine.checkedUrl(url); fail(url); } catch (IOException expected) { }
        }
    }

    @Test public void katakanaTitleAliasFindsKoreanNamedOriginal() throws Exception {
        assertEquals("아이덴티티",HangulReadingEngine.koreanTitleAlias("アイデンティティ"));
        assertEquals("루마",HangulReadingEngine.koreanTitleAlias("ルマ"));
        assertEquals("",HangulReadingEngine.koreanTitleAlias("青い空"));
        HangulReadingEngine.Result result=engine.resolve("アイデンティティ","宵崎奏",LINES,"",url -> {
            if(url.contains(HangulReadingEngine.encode("아이덴티티"))) return HTML;
            throw new IOException("not found");
        });
        assertTrue(result.valid());
        assertTrue(result.source.contains(HangulReadingEngine.encode("아이덴티티")));
    }

}
