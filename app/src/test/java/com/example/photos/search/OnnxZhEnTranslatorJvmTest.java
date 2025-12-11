package com.example.photos.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.sentencepiece.Model;
import com.sentencepiece.SentencePieceAlgorithm;
import com.sentencepiece.Scoring;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Test;

/**
 * JVM 端冒烟测试：不依赖 Android 设备，直接加载 assets 里的 opus_mt_zh_en 进行翻译。
 * 运行：./gradlew test --tests com.example.photos.search.OnnxZhEnTranslatorJvmTest
 */
public class OnnxZhEnTranslatorJvmTest {

    private static Path findAssetDir() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path p = cwd;
        for (int depth = 0; depth < 4 && p != null; depth++, p = p.getParent()) {
            Path candidate1 = p.resolve("app/src/main/assets/opus_mt_zh_en");
            Path candidate2 = p.resolve("src/main/assets/opus_mt_zh_en");
            if (Files.isDirectory(candidate1)) return candidate1;
            if (Files.isDirectory(candidate2)) return candidate2;
        }
        return null;
    }

    @Test
    public void translate_on_desktop_jvm() throws Exception {
        Path assetDir = findAssetDir();
        Assume.assumeTrue("assets missing: src/main/assets/opus_mt_zh_en", assetDir != null);

        DesktopTranslator translator = new DesktopTranslator(assetDir);
        String input = "今天天气不错，出去散步吧。";
        String output = translator.translate(input);
        translator.close();

        System.out.println("Input : " + input);
        System.out.println("Output: " + output);

        assertNotNull(output);
        assertFalse(output.trim().isEmpty());
        assertNotEquals("translation identical to input; likely fallback", input, output);
        assertFalse("output still contains CJK, translation likely failed", containsCjk(output));
    }

    // ---- 纯 JVM 版翻译器，复刻 OnnxZhEnTranslator 关键逻辑 ----

    private static final class DesktopTranslator implements Closeable {
        private static final int MAX_SRC_TOKENS = 128;
        private static final int MAX_NEW_TOKENS = 64;
        private static final int DEFAULT_PAD_ID = 65000;
        private static final int DEFAULT_EOS_ID = 0;
        private static final int DEFAULT_DECODER_START_ID = 65000;
        private static final int DEFAULT_UNK_ID = 1;

        private final OrtEnvironment env;
        private final OrtSession encoderSession;
        private final OrtSession decoderSession;
        private final Model srcSpModel;
        private final SentencePieceAlgorithm spAlgorithm;

        // HF vocab 映射
        private final Map<String, Integer> tokenToId = new HashMap<>();
        private String[] idToToken = new String[0];

        private int padId = DEFAULT_PAD_ID;
        private int eosId = DEFAULT_EOS_ID;
        private int decoderStartId = DEFAULT_DECODER_START_ID;
        private int unkId = DEFAULT_UNK_ID;

        DesktopTranslator(Path assetDir) throws IOException, OrtException {
            Path encoderPath = assetDir.resolve("encoder.onnx");
            Path decoderPath = assetDir.resolve("decoder.onnx");
            Path srcSpmPath = assetDir.resolve("source.spm");
            Path vocabPath = assetDir.resolve("vocab.json");

            assertTrue("encoder.onnx missing", Files.exists(encoderPath));
            assertTrue("decoder.onnx missing", Files.exists(decoderPath));
            assertTrue("source.spm missing", Files.exists(srcSpmPath));
            assertTrue("vocab.json missing", Files.exists(vocabPath));

            readIdsFromConfig(assetDir.resolve("config.json"));
            readVocab(vocabPath);

            env = OrtEnvironment.getEnvironment();
            encoderSession = env.createSession(encoderPath.toString(), new OrtSession.SessionOptions());
            decoderSession = env.createSession(decoderPath.toString(), new OrtSession.SessionOptions());

            srcSpModel = Model.parseFrom(srcSpmPath);
            // Unigram 解码，禁用 sampling
            spAlgorithm = new SentencePieceAlgorithm(false, Scoring.HIGHEST_SCORE);
        }

        String translate(String text) throws OrtException {
            if (text == null) return "";
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return trimmed;
            if (!containsCjk(trimmed)) return trimmed;

            int[] srcIds = encodeSrc(trimmed);
            if (srcIds.length == 0) {
                return trimmed;
            }

            int seqLen = srcIds.length;
            long[][] encoderInputIds = new long[1][seqLen];
            long[][] encoderAttentionMask = new long[1][seqLen];
            for (int i = 0; i < seqLen; i++) {
                encoderInputIds[0][i] = srcIds[i];
                encoderAttentionMask[0][i] = 1L;
            }

            float[][][] encoderHiddenStates;
            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, encoderInputIds);
                 OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, encoderAttentionMask)) {

                Map<String, OnnxTensor> encoderInputs = new HashMap<>();
                encoderInputs.put("input_ids", inputIdsTensor);
                encoderInputs.put("attention_mask", attentionMaskTensor);

                try (OrtSession.Result encoderOutputs = encoderSession.run(encoderInputs)) {
                    OnnxValue first = encoderOutputs.get(0);
                    encoderHiddenStates = (float[][][]) first.getValue();
                }
            }

            List<Integer> decodedIds = greedyDecode(encoderHiddenStates, encoderAttentionMask);
            return decodeTgt(decodedIds);
        }

        /**
         * 源侧：SentencePiece EncodeAsPieces → 用 vocab.json 做 piece → id 映射。
         */
        private int[] encodeSrc(String text) {
            List<Integer> spIds = srcSpModel.encodeNormalized(text, spAlgorithm);
            List<String> pieces = new ArrayList<>();
            for (int id : spIds) {
                String p = safeIdToPiece(srcSpModel, id);
                if (p != null) pieces.add(p);
            }
            System.out.println("SP4J src pieces: " + pieces);

            if (pieces.isEmpty()) {
                return new int[0];
            }
            int n = Math.min(pieces.size(), MAX_SRC_TOKENS - 1); // 预留一个 eos
            int[] out = new int[n + 1];
            for (int i = 0; i < n; i++) {
                String piece = pieces.get(i);
                Integer vid = tokenToId.get(piece);
                out[i] = (vid != null) ? vid : unkId;
            }
            out[n] = eosId;
            return out;
        }

        /**
         * 目标侧解码：
         * 1) 去掉 decoder_start / pad / eos
         * 2) vocab id -> token string
         * 3) 模拟 SentencePiece DecodePieces：'▁' 当空格
         */
        private String decodeTgt(List<Integer> allIds) {
            if (allIds == null || allIds.isEmpty()) {
                return "";
            }
            System.out.println("decoder raw vocab ids: " + allIds);

            int start = 0;
            int end = allIds.size();
            if (allIds.get(0) == decoderStartId) {
                start = 1;
            }
            while (end > start) {
                int v = allIds.get(end - 1);
                if (v == padId || v == eosId) {
                    end--;
                } else {
                    break;
                }
            }
            if (end <= start) {
                return "";
            }

            List<String> tokens = new ArrayList<>();
            for (int i = start; i < end; i++) {
                int vocabId = allIds.get(i);
                String tok = safeIdToToken(vocabId);
                if (tok == null) continue;
                // 过滤特殊 token
                if ("<pad>".equals(tok) || "</s>".equals(tok) || "<unk>".equals(tok)) {
                    continue;
                }
                tokens.add(tok);
            }

            System.out.println("tgt tokens (vocab -> pieces): " + tokens);
            return decodePiecesLikeSentencePiece(tokens);
        }

        /**
         * 模拟 SentencePiece 的 DecodePieces 行为。
         */
        private static String decodePiecesLikeSentencePiece(List<String> tokens) {
            StringBuilder sb = new StringBuilder();
            for (String tok : tokens) {
                if (tok == null || tok.isEmpty()) continue;
                if (tok.charAt(0) == '▁') {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(tok.substring(1));
                } else {
                    sb.append(tok);
                }
            }
            return sb.toString().trim();
        }

        private List<Integer> greedyDecode(float[][][] encoderHiddenStates,
                                           long[][] encoderAttentionMask) throws OrtException {
            List<Integer> generated = new ArrayList<>();
            generated.add(decoderStartId);

            for (int step = 0; step < MAX_NEW_TOKENS; step++) {
                long[][] decoderInputIds = new long[1][generated.size()];
                for (int i = 0; i < generated.size(); i++) {
                    decoderInputIds[0][i] = generated.get(i);
                }

                float[][][] logits;
                try (OnnxTensor decoderInputTensor = OnnxTensor.createTensor(env, decoderInputIds);
                     OnnxTensor encoderHiddenTensor = OnnxTensor.createTensor(env, encoderHiddenStates);
                     OnnxTensor encoderMaskTensor = OnnxTensor.createTensor(env, encoderAttentionMask)) {

                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("input_ids", decoderInputTensor);
                    inputs.put("encoder_hidden_states", encoderHiddenTensor);
                    inputs.put("encoder_attention_mask", encoderMaskTensor);

                    try (OrtSession.Result decoderOutputs = decoderSession.run(inputs)) {
                        OnnxValue value = decoderOutputs.get(0);
                        logits = (float[][][]) value.getValue();
                    }
                }

                int tgtLen = logits[0].length;
                float[] lastLogits = logits[0][tgtLen - 1];
                if (step == 0) {
                    System.out.println("decoder logits last dim = " + lastLogits.length);
                }
                int nextId = argmax(lastLogits);
                if (nextId == eosId || nextId == padId) {
                    break;
                }
                generated.add(nextId);
            }
            System.out.println("generated vocab ids: " + generated);
            return generated;
        }

        private static int argmax(float[] logits) {
            int bestIdx = 0;
            float bestVal = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < logits.length; i++) {
                float v = logits[i];
                if (v > bestVal) {
                    bestVal = v;
                    bestIdx = i;
                }
            }
            return bestIdx;
        }

        private void readIdsFromConfig(Path configPath) {
            try {
                String json = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
                padId = findInt(json, "\"pad_token_id\"\\s*:\\s*(\\d+)", DEFAULT_PAD_ID);
                eosId = findInt(json, "\"eos_token_id\"\\s*:\\s*(\\d+)", DEFAULT_EOS_ID);
                decoderStartId = findInt(json, "\"decoder_start_token_id\"\\s*:\\s*(\\d+)", DEFAULT_DECODER_START_ID);
                unkId = findInt(json, "\"unk_token_id\"\\s*:\\s*(\\d+)", DEFAULT_UNK_ID);
            } catch (Exception e) {
                padId = DEFAULT_PAD_ID;
                eosId = DEFAULT_EOS_ID;
                decoderStartId = DEFAULT_DECODER_START_ID;
                unkId = DEFAULT_UNK_ID;
            }
        }

        private static int findInt(String json, String pattern, int fallback) {
            Matcher m = Pattern.compile(pattern).matcher(json);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignore) {
                    return fallback;
                }
            }
            return fallback;
        }

        /**
         * 关键修复：对 JSON 里的 "\u2581" 等序列做反转义，还原成真正的 Unicode 字符。
         */
        private void readVocab(Path vocabPath) {
            try {
                String json = new String(Files.readAllBytes(vocabPath), StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\d+)").matcher(json);
                int maxId = -1;
                while (matcher.find()) {
                    String rawToken = matcher.group(1);
                    String token = unescapeJsonString(rawToken);
                    int id = Integer.parseInt(matcher.group(2));
                    tokenToId.put(token, id);
                    if (id > maxId) maxId = id;
                }
                if (maxId >= 0) {
                    idToToken = new String[maxId + 1];
                    for (Map.Entry<String, Integer> e : tokenToId.entrySet()) {
                        int id = e.getValue();
                        if (id >= 0 && id < idToToken.length) {
                            idToToken[id] = e.getKey();
                        }
                    }
                }
            } catch (Exception e) {
                tokenToId.clear();
                idToToken = new String[0];
            }
        }

        private static String unescapeJsonString(String s) {
            StringBuilder sb = new StringBuilder();
            int len = s.length();
            for (int i = 0; i < len; i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < len) {
                    char n = s.charAt(i + 1);
                    if (n == 'u' && i + 5 < len) {
                        String hex = s.substring(i + 2, i + 6);
                        try {
                            int cp = Integer.parseInt(hex, 16);
                            sb.append((char) cp);
                            i += 5;
                            continue;
                        } catch (NumberFormatException ignore) {
                            // fall through
                        }
                    } else {
                        i++;
                        switch (n) {
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            default:
                                sb.append(n);
                                break;
                        }
                        continue;
                    }
                }
                sb.append(c);
            }
            return sb.toString();
        }

        private static String safeIdToPiece(Model model, int id) {
            try {
                return model.getTokenById(id);
            } catch (Exception e) {
                return null;
            }
        }

        private String safeIdToToken(int vocabId) {
            if (vocabId < 0 || vocabId >= idToToken.length) return null;
            return idToToken[vocabId];
        }

        @Override
        public void close() throws IOException {
            try {
                encoderSession.close();
            } catch (Exception ignore) {
            }
            try {
                decoderSession.close();
            } catch (Exception ignore) {
            }
            try {
                env.close();
            } catch (Exception ignore) {
            }
        }
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }
}
