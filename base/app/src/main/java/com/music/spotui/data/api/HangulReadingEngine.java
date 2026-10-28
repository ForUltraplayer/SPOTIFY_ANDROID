// @file HangulReadingEngine.java
// @brief Match existing Korean readings to original Japanese lyrics, including covers.
package com.music.spotui.data.api;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

final class HangulReadingEngine {
    static final String MISSING = "(발음 자료 없음)";
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff]");
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");
    private static final Pattern COVER = Pattern.compile("(?i)\\bcover(?:ed)?\\b|歌ってみた|カバー|커버(?:곡)?");
    private static final Set<String> BLOCKS = new HashSet<>(Arrays.asList(
            "br", "p", "div", "tr", "li", "h1", "h2", "h3", "h4", "hr", "table", "td", "th"));
    private static final String OLD = "歸拂戾敎隱殘當氣會黑變淸顏淚體廣聲實覺繼續戀惡發邊點國區滿亂從專兩應眞樂處戰爭爲橫樣噓";
    private static final String NEW = "帰払戻教隠残当気会黒変清顔涙体広声実覚継続恋悪発辺点国区満乱従専両応真楽処戦争為横様嘘";
    private Tokenizer tokenizer;
    private static final ThreadLocal<Long> requestDeadline = new ThreadLocal<>();
    private final Map<String, String> kanaCache = new LinkedHashMap<String, String>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 4096; }
    };

    interface Fetcher { String get(String url) throws IOException; }

    static final class Page {
        final List<String> lines;
        final List<String> links;
        final String title;
        Page(List<String> lines, List<String> links, String title) {
            this.lines = lines; this.links = links; this.title = title;
        }
    }

    static final class Pair {
        final String original, reading;
        Pair(String original, String reading) { this.original = original; this.reading = cleanReading(reading); }
    }

    static final class Result {
        final List<String> readings;
        final int matched, total;
        final String source;
        Result(List<String> readings, int matched, int total, String source) {
            this.readings = readings; this.matched = matched; this.total = total; this.source = source;
        }
        boolean valid() { return total >= 3 && matched >= Math.min(5, total) && matched >= .85 * total; }
    }

    static boolean japanese(String text) { return text != null && JAPANESE.matcher(text).find(); }
    static String clean(String text) {
        return text.replace("\u200b", "").replaceAll("\\[\\d+\\]", "").replaceAll("\\s+", " ").trim();
    }
    static String cleanReading(String text) {
        return text.replaceFirst("^\\s*(?:〔문장\\s*전체〕|\\[문장\\s*전체\\])\\s*", "");
    }
    static String normalize(String text) {
        String value = Normalizer.normalize(clean(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            int old = OLD.indexOf(c);
            if (old >= 0) c = NEW.charAt(old);
            if (c >= 'ァ' && c <= 'ヶ') c -= 0x60;
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString().replace("匂い", "におい").replaceAll("([か-もや-ろ])\\1+(?=っ)", "$1");
    }

    static String checkedUrl(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(host.equals("namu.wiki") || host.endsWith(".tistory.com")
                    || (host.equals("haneum.site") && uri.getPath().matches("/songs(?:/[^/]+)?")))) {
                throw new IOException("한음, 나무위키 또는 티스토리 HTTPS 가사 주소를 입력해 주세요.");
            }
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null).toASCIIString();
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            throw new IOException("문서 주소를 확인해 주세요.");
        }
    }

    static String fetch(String input) throws IOException {
        String url = checkedUrl(input);
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("조회 취소");
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(remainingTimeout());
                connection.setReadTimeout(remainingTimeout());
                connection.setRequestProperty("User-Agent", "ivLyricsHangul/0.1 (personal lyric reader)");
                connection.setRequestProperty("Accept", "text/html");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("문서 이동 주소 없음");
                    url = checkedUrl(URI.create(url).resolve(location).toString());
                    continue;
                }
                if (status != 200) throw new IOException("문서 조회 HTTP " + status);
                String type = connection.getContentType();
                if (type == null || !(type.startsWith("text/html") || type.startsWith("application/xhtml+xml")))
                    throw new IOException("HTML 문서가 아닙니다.");
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("조회 취소");
                        remainingTimeout();
                        if (out.size() + count > 3_000_000) throw new IOException("문서 크기 제한 초과");
                        out.write(buffer, 0, count);
                    }
                    return out.toString("UTF-8");
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("문서 이동 횟수 초과");
    }

    private static int remainingTimeout() throws IOException {
        Long deadline = requestDeadline.get();
        long remaining = deadline == null ? 6500 : (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("조회 취소 또는 시간 초과");
        return (int)Math.min(6500, remaining);
    }

    static Page parse(String html) {
        Document doc = Jsoup.parse(html);
        String title = doc.title();
        doc.select("script,style,rt,rp,sup,nav,footer,noscript,a[href^=\"#fn\"],a[href^=\"#rfn\"],a[href^=\"#footnote\"]").remove();
        List<String> links = new ArrayList<>();
        for (Element a : doc.select("a[href^=\"/w/\"],a[href^=\"/songs/\"]")) {
            String href = a.attr("href").split("#", 2)[0];
            if (!links.contains(href)) links.add(href);
        }
        List<String> haneum = new ArrayList<>();
        for (Element row : doc.select("div.border-b")) {
            List<String> columns = new ArrayList<>();
            for (Element child : row.children()) if (child.normalName().equals("p")) columns.add(clean(child.text()));
            if (columns.size() == 3) {
                haneum.add(columns.get(1)); haneum.add(columns.get(0)); haneum.add(columns.get(2));
            }
        }
        if (!haneum.isEmpty()) return new Page(haneum, links, title);
        StringBuilder body = new StringBuilder();
        appendVisible(doc.body(), body);
        List<String> lines = new ArrayList<>();
        for (String line : body.toString().split("\n")) if (!clean(line).isEmpty() && !clean(line).matches("[xX×]\\s*\\d{1,3}")) lines.add(clean(line));
        return new Page(lines, links, title);
    }

    private static void appendVisible(Node node, StringBuilder out) {
        if (node instanceof TextNode) {
            String text = ((TextNode) node).getWholeText();
            if (!clean(text).matches("[xX×]\\s*\\d{1,3}")) out.append(text);
            return;
        }
        if (BLOCKS.contains(node.nodeName())) out.append('\n');
        for (Node child : node.childNodes()) appendVisible(child, out);
        if (BLOCKS.contains(node.nodeName())) out.append('\n');
    }

    List<Pair> pairs(Page page) {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i + 2 < page.lines.size(); i++) {
            String original = page.lines.get(i), reading = page.lines.get(i + 1), translation = page.lines.get(i + 2);
            if (!japanese(original) || HANGUL.matcher(original).find() || original.length() > 350
                    || !HANGUL.matcher(reading).find() || japanese(reading) || reading.length() > 500
                    || !HANGUL.matcher(translation).find() || japanese(translation) || normalize(original).length() < 2) continue;
            if (readingSimilarity(original, reading) >= .45) pairs.add(new Pair(original, reading));
        }
        return pairs;
    }

    String kana(String text) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        String key = normalize(text), cached = kanaCache.get(key);
        if (cached != null) return cached;
        if (tokenizer == null) tokenizer = new Tokenizer();
        StringBuilder result = new StringBuilder();
        for (Token token : tokenizer.tokenize(key)) {
            String reading = token.getReading();
            result.append(reading == null || "*".equals(reading) ? token.getSurface() : reading);
        }
        String value = normalize(result.toString());
        kanaCache.put(key, value);
        return value;
    }

    private static final Map<String, String> ROMAJI = new HashMap<>();
    private static final Map<String, String> TITLE_HANGUL = new HashMap<>();
    static {
        String chars = "あいうえおかきくけこがぎぐげごさしすせそざじずぜぞたちつてとだぢづでどなにぬねのはひふへほばびぶべぼぱぴぷぺぽまみむめもやゆよらりるれろわをんぁぃぅぇぉゔ";
        String[] sounds = ("a i u e o ka ki ku ke ko ga gi gu ge go sa shi su se so za ji zu ze zo ta chi tsu te to da ji zu de do na ni nu ne no ha hi fu he ho ba bi bu be bo pa pi pu pe po ma mi mu me mo ya yu yo ra ri ru re ro wa o n a i u e o vu").split(" ");
        for (int i = 0; i < chars.length(); i++) ROMAJI.put(chars.substring(i, i + 1), sounds[i]);
        String[] korean = ("아 이 우 에 오 카 키 쿠 케 코 가 기 구 게 고 사 시 스 세 소 자 지 즈 제 조 타 치 츠 테 토 다 지 즈 데 도 나 니 누 네 노 하 히 후 헤 호 바 비 부 베 보 파 피 푸 페 포 마 미 무 메 모 야 유 요 라 리 루 레 로 와 오 ㄴ 아 이 우 에 오 부").split(" ");
        for (int i = 0; i < chars.length(); i++) TITLE_HANGUL.put(chars.substring(i,i+1),korean[i]);
        String[] roots = "き ぎ し じ ち に ひ び ぴ み り".split(" ");
        String[] stems = "ky gy sh j ch ny hy by py my ry".split(" ");
        for (int i = 0; i < roots.length; i++) {
            ROMAJI.put(roots[i] + "ゃ", stems[i] + "a");
            ROMAJI.put(roots[i] + "ゅ", stems[i] + "u");
            ROMAJI.put(roots[i] + "ょ", stems[i] + "o");
        }
        String[] joined = "캬 큐 쿄 갸 규 교 샤 슈 쇼 자 주 조 차 추 초 냐 뉴 뇨 햐 휴 효 뱌 뷰 뵤 퍄 퓨 표 먀 뮤 묘 랴 류 료".split(" ");
        for (int i=0;i<roots.length;i++) for(int j=0;j<3;j++) TITLE_HANGUL.put(roots[i]+"ゃゅょ".charAt(j),joined[i*3+j]);
        String[] foreign = "てぃ 티 でぃ 디 とぅ 투 どぅ 두 ふぁ 파 ふぃ 피 ふぇ 페 ふぉ 포 うぃ 위 うぇ 웨 うぉ 워 しぇ 셰 じぇ 제 ちぇ 체 ゔぁ 바 ゔぃ 비 ゔぇ 베 ゔぉ 보 ぢゃ 자 ぢゅ 주 ぢょ 조".split(" ");
        for(int i=0;i<foreign.length;i+=2) TITLE_HANGUL.put(foreign[i],foreign[i+1]);
    }

    /** Search alias only: never used as the lyric reading shown to the user. */
    static String koreanTitleAlias(String title) {
        String text=normalize(title);
        StringBuilder out=new StringBuilder();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='ー') continue;
            if(c=='ん' || c=='っ') {
                if(out.length()==0) return "";
                char previous=out.charAt(out.length()-1);
                if(previous<'가' || previous>'힣' || (previous-'가')%28!=0) return "";
                out.setCharAt(out.length()-1,(char)(previous+(c=='ん'?4:19)));
                continue;
            }
            String value=i+1<text.length()?TITLE_HANGUL.get(text.substring(i,i+2)):null;
            if(value!=null) i++; else value=TITLE_HANGUL.get(String.valueOf(c));
            if(value==null) return "";
            out.append(value);
        }
        return out.toString();
    }

    static String romanize(String text) {
        StringBuilder out = new StringBuilder();
        boolean doubled = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'っ') { doubled = true; continue; }
            String sound = i + 1 < text.length() ? ROMAJI.get(text.substring(i, i + 2)) : null;
            if (sound != null) i++;
            else sound = ROMAJI.get(String.valueOf(c));
            if (sound == null) sound = c < 128 && Character.isLetter(c) ? String.valueOf(c) : "";
            if (doubled && !sound.isEmpty()) out.append(sound.charAt(0));
            doubled = false;
            out.append(sound);
        }
        return out.toString();
    }

    double readingSimilarity(String japanese, String korean) {
        String[] onset = "g k n d t r m b p s s _ j j ch k t p h".split(" ");
        String[] vowel = "a ae ya yae eo e yeo ye o wa wae oe yo u wo we wi yu eu ui i".split(" ");
        String[] last = "_ k k k n n n t r k m p r r p r m p p t t n t t k t p h".split(" ");
        StringBuilder latin = new StringBuilder();
        for (char c : korean.toCharArray()) {
            int value = c - 0xac00;
            if (value >= 0 && value < 11172) latin.append(onset[value / 588]).append(vowel[value % 588 / 28]).append(last[value % 28]);
            else if (c < 128 && Character.isLetter(c)) latin.append(Character.toLowerCase(c));
        }
        return similarity(soundKey(romanize(kana(japanese))), soundKey(latin.toString().replace("_", "")));
    }

    private static String soundKey(String value) {
        String[][] replacements = {{"shi","si"},{"chi","ti"},{"tsu","cu"},{"cheu","cu"},{"fu","hu"},{"ji","zi"},
                {"syo","sio"},{"sho","sio"},{"eu","u"},{"eo","o"},{"ou","o"}};
        for (String[] pair : replacements) value = value.replace(pair[0], pair[1]);
        return value.replace('g','k').replace('d','t').replace('b','p').replaceAll("(.)\\1+", "$1");
    }

    // SequenceMatcher-style ordered matching blocks. Bound CPU and memory for untrusted pages.
    static int[] mapping(String a, String b) {
        int[] map = new int[a.length()]; Arrays.fill(map, -1);
        if (a.length() > 12000 || b.length() > 24000) return map;
        ArrayDeque<int[]> work = new ArrayDeque<>(); work.add(new int[]{0, a.length(), 0, b.length()});
        long budget = 80_000_000;
        while (!work.isEmpty()) {
            int[] part = work.removeFirst();
            long cells = (long)(part[1]-part[0]) * (part[3]-part[2]);
            if ((budget -= cells) < 0 || Thread.currentThread().isInterrupted()) break;
            int width = part[3] - part[2], best = 0, ai = 0, bi = 0;
            int[] previous = new int[width + 1], current = new int[width + 1];
            for (int i = part[0]; i < part[1]; i++) {
                Arrays.fill(current, 0);
                for (int j = 0; j < width; j++) if (a.charAt(i) == b.charAt(part[2] + j)) {
                    current[j + 1] = previous[j] + 1;
                    if (current[j + 1] > best) { best = current[j + 1]; ai = i - best + 1; bi = part[2] + j - best + 1; }
                }
                int[] swap = previous; previous = current; current = swap;
            }
            if (best == 0) continue;
            for (int i = 0; i < best; i++) map[ai + i] = bi + i;
            if (part[0] < ai && part[2] < bi) work.add(new int[]{part[0], ai, part[2], bi});
            if (ai + best < part[1] && bi + best < part[3]) work.add(new int[]{ai + best, part[1], bi + best, part[3]});
        }
        return map;
    }

    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int matches = 0; for (int n : mapping(a,b)) if (n >= 0) matches++;
        return 2.0 * matches / (a.length() + b.length());
    }

    Result align(List<String> originals, List<Pair> pairs, String source, boolean byKana) {
        Function<String,String> keyOf = byKana ? this::kana : HangulReadingEngine::normalize;
        List<String> keys = new ArrayList<>();
        Map<String,String> lookup = new LinkedHashMap<>();
        StringBuilder page = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();
        for (Pair pair : pairs) {
            String key = keyOf.apply(pair.original); keys.add(key); lookup.putIfAbsent(key, pair.reading);
            ranges.add(new int[]{page.length(), page.length() + key.length()}); page.append(key);
        }
        for (int width = 2; width <= 3; width++) for (int i = 0; i + width <= keys.size(); i++) {
            StringBuilder key = new StringBuilder(), text = new StringBuilder();
            for (int j = i; j < i + width; j++) { key.append(keys.get(j)); if (j > i) text.append(' '); text.append(pairs.get(j).reading); }
            lookup.putIfAbsent(key.toString(), text.toString());
        }
        List<String> songKeys = new ArrayList<>(); StringBuilder song = new StringBuilder();
        for (String row : originals) { String key = japanese(row) ? keyOf.apply(row) : ""; songKeys.add(key); song.append(key); }
        int[] map = mapping(song.toString(), page.toString());
        List<String> output = new ArrayList<>(); int position = 0, matched = 0, total = 0;
        for (int index = 0; index < originals.size(); index++) {
            String original = originals.get(index), key = songKeys.get(index);
            if (!japanese(original)) { output.add(original); continue; }
            total++;
            String reading = lookup.get(key);
            if (reading == null && key.length() >= 6) {
                double best = .939999; String candidate = null; boolean ambiguous = false;
                for (int j = 0; j < keys.size(); j++) {
                    double size = 1.0 * keys.get(j).length() / key.length();
                    if (size < .9 || size > 1.1) continue;
                    double score = similarity(key, keys.get(j));
                    if (score > best) { best = score; candidate = pairs.get(j).reading; ambiguous = false; }
                    else if (score == best && candidate != null && !candidate.equals(pairs.get(j).reading)) ambiguous = true;
                }
                if (!ambiguous) reading = candidate;
            }
            if (reading == null && !key.isEmpty()) {
                int first = Integer.MAX_VALUE, last = -1, count = 0;
                for (int i = position; i < position + key.length(); i++) if (map[i] >= 0) { count++; first = Math.min(first,map[i]); last = Math.max(last,map[i]); }
                if (count >= .94 * key.length() && last + 1 - first <= key.length() * 1.2) {
                    List<String> selected = new ArrayList<>();
                    for (int j = 0; j < ranges.size(); j++) if (ranges.get(j)[0] <= last && ranges.get(j)[1] > first) selected.add(pairs.get(j).reading);
                    if (!selected.isEmpty() && selected.size() <= 3) reading = String.join(" ", selected);
                }
            }
            position += key.length();
            if (reading == null) output.add(MISSING); else { output.add(cleanReading(reading)); matched++; }
        }
        return new Result(output, matched, total, source);
    }

    Result match(List<String> originals, Page page, String source) {
        List<Pair> pairs = pairs(page);
        Result raw = align(originals, pairs, source, false);
        if (raw.valid() || pairs.isEmpty()) return raw;
        Result phonetic = align(originals, pairs, source, true);
        return phonetic.matched > raw.matched ? phonetic : raw;
    }

    private static final List<String> KNOWN_ARTISTS = Arrays.asList("kenshiyonezu","米津玄師","요네즈켄시","yoasobi");

    static List<String> titles(String title, String artist) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        // 「」 stay as-is: they are usually part of a Japanese title and can nest inside (feat. ...).
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).replace('【','[').replace('】',']');
        String text = normalized.replaceAll("(?i)[\\[(][^\\])]*(?:cover|歌ってみた|カバー|커버)[^\\])]*[\\])]", " ");
        text = text.replaceAll("(?i)\\s*[\\[(]\\s*(?:feat\\.?|ft\\.?)\\s+[^\\])]*[\\])]", "");
        text = text.replaceAll("(?i)\\s*[-–—]\\s*(?:feat\\.?|ft\\.?)\\s+.*$", "");
        text = text.replaceAll("(?i)^\\s*(?:cover(?:ed)?\\s+(?:of\\s+)?|カバー[:：\\s]+|커버[:：\\s]+)", "");
        text = text.replaceAll("(?i)\\s*(?:[/|—–-]\\s*)?(?:covered\\s+by|cover\\s+by)\\b.*$", "");
        text = text.replaceAll("(?i)\\s*[-(]\\s*(?:remaster|live|radio|instrumental|edit|acoustic).*", "");
        text = text.replaceAll("(?i)\\s*(?:[/|—–-]\\s*)?(?:cover(?:\\s+ver(?:sion)?\\.?)?|歌ってみた|カバー|커버(?:곡)?)\\s*$", "");
        // A slash is often part of the title itself (ハロ/ハワユ, D/N/A, W / X / Y);
        // split on it only when one side is an artist name.
        Set<String> artists = new HashSet<>(KNOWN_ARTISTS);
        artists.add(normalize(artist));
        for (String name : artist.split("[,/&、]")) if (normalize(name).length() >= 2) artists.add(normalize(name));
        artists.remove("");
        List<String> pieces = new ArrayList<>();
        for (String part : text.split("\\s+[-–—|]\\s*")) {
            List<String> split = Arrays.asList(part.split("\\s*[/／]\\s*"));
            if (split.stream().anyMatch(piece -> artists.contains(normalize(piece)))) pieces.addAll(split);
            else pieces.add(part.replaceAll("^[\\s/|-]+|[\\s/|-]+$", ""));
        }
        for (boolean japaneseFirst : new boolean[]{true,false}) for (String part : pieces) {
            String value = part.trim(), key = normalize(value);
            if (value.isEmpty() || COVER.matcher(value).matches() || normalize(artist).contains(key)
                    || KNOWN_ARTISTS.contains(key)) continue;
            if (!japaneseFirst || japanese(value)) candidates.add(value);
        }
        String koreanAlias=koreanTitleAlias(text.trim());
        if(!koreanAlias.isEmpty()) candidates.add(koreanAlias);
        candidates.add(text.trim()); candidates.add(title.trim()); candidates.remove("");
        return new ArrayList<>(candidates).subList(0, Math.min(5,candidates.size()));
    }

    static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
    }

    Result resolve(String title, String artist, List<String> originals, String sourceUrl, Fetcher fetcher) throws IOException {
        return resolve(title, artist, originals, sourceUrl, "", fetcher);
    }

    Result resolve(String title, String artist, List<String> originals, String sourceUrl, String learnedUrl, Fetcher fetcher) throws IOException {
        return resolve(title, artist, originals, sourceUrl, learnedUrl, fetcher, 32_000L);
    }

    Result resolve(String title, String artist, List<String> originals, String sourceUrl, String learnedUrl, Fetcher fetcher, long remainingMillis) throws IOException {
        if (title.length() > 300 || artist.length() > 500 || String.join("\n", originals).length() > 40000)
            throw new IOException("곡 정보 또는 가사 길이 제한 초과");
        List<String> titles = titles(title, artist);
        ArrayDeque<String> pending = new ArrayDeque<>();
        boolean manual = sourceUrl != null && !sourceUrl.trim().isEmpty();
        if (manual) pending.add(checkedUrl(sourceUrl.trim()));
        else {
            if (learnedUrl != null && !learnedUrl.isEmpty()) pending.add(checkedUrl(learnedUrl));
            for (String name : titles.subList(0, Math.min(3,titles.size()))) {
            // A bracketed or feat. variant is never a document name; leave it to search.
            if (name.matches("(?is).*(?:[\\[\\]()（）]|\\bfeat\\b|\\bft\\.).*")) continue;
            pending.add("https://namu.wiki/w/" + encode(name));
            if ((title + artist).toLowerCase(Locale.ROOT).matches(".*(?:kenshi|yonezu|米津玄師|요네즈).*"))
                pending.add("https://namu.wiki/w/" + encode(name + "(요네즈 켄시)"));
            }
        }
        List<String[]> searches = new ArrayList<>();
        for (String name : titles.subList(0, Math.min(2,titles.size()))) searches.add(new String[]{"https://haneum.site/songs?q=" + encode(name), "haneum"});
        if (!titles.isEmpty()) searches.add(new String[]{titles.get(0), "title"});
        for (String name : titles.subList(0, Math.min(2,titles.size()))) searches.add(new String[]{name, "content"});
        int fragments = 0;
        for (String row : originals) if (japanese(row) && !HANGUL.matcher(row).find() && normalize(row).length() >= 10 && fragments++ < 2)
            searches.add(new String[]{row.substring(0,Math.min(100,row.length())), "content"});
        Set<String> visited = new HashSet<>();
        if (remainingMillis <= 0) throw new IOException("조회 시간 초과");
        long deadline = System.nanoTime() + Math.min(32_000L, remainingMillis) * 1_000_000L;
        requestDeadline.set(deadline);
        int search = 0;
        try {
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted() && visited.size() < 20) {
            if (pending.isEmpty()) {
                if (manual || search >= searches.size()) break;
                String[] query = searches.get(search++);
                boolean haneum = query[1].equals("haneum");
                String url = haneum ? query[0] : "https://namu.wiki/Search?q=" + encode(query[0]) + "&namespace=" + encode("문서") + "&target=" + query[1];
                if (!visited.add(url)) continue;
                try {
                    Page page = parse(fetcher.get(url)); int count = 0;
                    for (String link : page.links) {
                        if (haneum != link.startsWith("/songs/")) continue;
                        pending.add((haneum ? "https://haneum.site" : "https://namu.wiki") + link);
                        if (++count >= (haneum ? 4 : 5)) break;
                    }
                }
                catch (IOException ignored) { }
                continue;
            }
            String url = pending.removeFirst(); if (!visited.add(url)) continue;
            try {
                String markup = fetcher.get(url);
                if (Thread.currentThread().isInterrupted()) throw new IOException("조회 취소");
                if (System.nanoTime() >= deadline) throw new IOException("조회 시간 초과");
                Page page = parse(markup); Result result = match(originals, page, url);
                if (Thread.currentThread().isInterrupted()) throw new IOException("조회 취소");
                if (result.valid()) return result;
                Set<String> names = new HashSet<>();
                for (String candidate : titles) names.add(normalize(candidate));
                names.add(normalize(page.title.replaceFirst("\\s*[-–]\\s*나무위키\\s*$", "").split("\\(", 2)[0]));
                if (!manual) for (String link : page.links) {
                    if (!link.startsWith("/w/")) continue;
                    String name;
                    try { name = java.net.URLDecoder.decode(link.substring(3), "UTF-8"); } catch (Exception e) { continue; }
                    if (!name.contains("(")) continue;
                    String base = normalize(name.substring(0,name.indexOf('(')));
                    if (names.contains(base) && !visited.contains("https://namu.wiki"+link)) {
                        if (pending.size() < 12) pending.addFirst("https://namu.wiki"+link);
                    }
                }
            } catch (IOException ignored) { }
        }
        } finally { requestDeadline.remove(); }
        throw new IOException("원문과 85% 이상 일치하는 한글 발음 자료를 찾지 못했습니다. 현재 곡 설정에서 자료 주소를 지정해 주세요.");
    }

    List<String> completeAutomatically(List<String> originals, List<String> authored) {
        List<String> result = new ArrayList<>();
        for (int row = 0; row < originals.size(); row++) {
            String original = originals.get(row);
            String existing = authored == null ? (japanese(original) ? MISSING : original) : authored.get(row);
            if (!MISSING.equals(existing) || !japanese(original)) { result.add(existing); continue; }
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (tokenizer == null) tokenizer = new Tokenizer();
            StringBuilder readings = new StringBuilder();
            for (Token token : tokenizer.tokenize(original)) {
                String surface = token.getSurface(), reading = token.getReading();
                if (reading == null || reading.equals("*")) reading = surface;
                if (surface.equals("は") && token.getPartOfSpeechLevel1().equals("助詞")) reading = "ワ";
                if (surface.equals("へ") && token.getPartOfSpeechLevel1().equals("助詞")) reading = "エ";
                // A kanji verb's okurigana う (思う, 迷う) is spoken, unlike a long vowel.
                if (surface.endsWith("う") && reading.endsWith("ウ") && SMALL.indexOf(reading.charAt(Math.max(0, reading.length() - 2))) < 0
                        && surface.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff))
                    reading = reading.substring(0, reading.length() - 1) + VERB_U;
                readings.append(reading);
            }
            // Convert once so a sokuon or long vowel at a token boundary joins the previous syllable.
            String value = kanaToHangul(readings.toString());
            result.add(HANGUL.matcher(value).find() ? value : MISSING);
        }
        return result;
    }

    // Marks a verb-ending う that is pronounced; kanaToHangul writes it as 우 instead of dropping it.
    private static final char VERB_U = '\ue000';
    private static final String SMALL = "ぁぃぅぇぉゃゅょァィゥェォャュョ";

    /** Follows authored lyric readings: long vowels dropped (どうして -> 도시테), っ as ㅅ, ん as ㄴ. */
    static String kanaToHangul(String input) {
        String text = Normalizer.normalize(input, Normalizer.Form.NFKC);
        StringBuilder hiragana = new StringBuilder();
        for (char c : text.toCharArray()) hiragana.append(c >= 'ァ' && c <= 'ヶ' ? (char)(c - 0x60) : c);
        text = hiragana.toString();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            int last = result.length() - 1, offset = last >= 0 ? result.charAt(last) - '가' : -1;
            int vowel = offset >= 0 && offset < 11172 ? offset % 588 / 28 : -1;
            boolean open = vowel >= 0 && offset % 28 == 0;
            // う extends o, yo, u, yu and eu; お extends o and yo; え extends e and ye. いい keeps its second い.
            boolean lengthens = c == 'う' ? vowel == 8 || vowel == 12 || vowel == 13 || vowel == 17 || vowel == 18
                    : c == 'お' ? vowel == 8 || vowel == 12 : c == 'え' && (vowel == 5 || vowel == 7);
            if (c == VERB_U) result.append('우');
            else if (c == 'っ' || c == 'ん') {
                if (open) result.setCharAt(last, (char)(result.charAt(last) + (c == 'ん' ? 4 : 19)));
                else result.append(c == 'ん' ? "응" : "읏");
            } else if (c == 'ー') {
                // A lengthened e is written as 이 (ゲーム -> 게이무); other long vowels are dropped.
                if (vowel == 5 || vowel == 7) result.append('이');
                else if (vowel < 0) result.append('ー');
            } else if (open && lengthens && SMALL.indexOf(next) < 0) {
                continue;
            } else {
                String pair = i + 1 < text.length() ? TITLE_HANGUL.get(text.substring(i, i + 2)) : null;
                if (pair != null) { result.append(pair); i++; }
                else result.append(c == 'す' && next == 'ー' ? "수" : TITLE_HANGUL.getOrDefault(String.valueOf(c), String.valueOf(c)));
            }
        }
        return result.toString();
    }

}
