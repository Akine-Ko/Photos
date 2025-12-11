package com.example.photos.search;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.sentencepiece.Model;
import com.sentencepiece.SentencePieceAlgorithm;
import com.sentencepiece.Scoring;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android 端中→英翻译器：封装 opus-mt-zh-en 的 ONNX + SentencePiece + vocab.json glue 逻辑。
 *
 * 资产路径约定：
 * app/src/main/assets/opus_mt_zh_en/
 *   encoder.onnx
 *   decoder.onnx
 *   source.spm
 *   vocab.json
 *   config.json
 */
public final class OnnxZhEnTranslator implements Closeable {

    private static final String TAG = "OnnxZhEnTranslator";

    private static volatile OnnxZhEnTranslator INSTANCE;

    public static OnnxZhEnTranslator getInstance(Context context) throws IOException, OrtException {
        if (INSTANCE == null) {
            synchronized (OnnxZhEnTranslator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OnnxZhEnTranslator(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    public static void destroy() {
        OnnxZhEnTranslator inst = INSTANCE;
        INSTANCE = null;
        if (inst != null) {
            try {
                inst.close();
            } catch (Exception ignore) {
            }
        }
    }

    // ---- 配置常量 ----
    private static final String ASSET_DIR = "opus_mt_zh_en";
    private static final String ENCODER_NAME = "encoder.onnx";
    private static final String DECODER_NAME = "decoder.onnx";
    private static final String SRC_SPM_NAME = "source.spm";
    private static final String VOCAB_NAME = "vocab.json";
    private static final String CONFIG_NAME = "config.json";

    private static final int MAX_SRC_TOKENS = 128;
    private static final int MAX_NEW_TOKENS = 64;

    private static final int DEFAULT_PAD_ID = 65000;
    private static final int DEFAULT_EOS_ID = 0;
    private static final int DEFAULT_DECODER_START_ID = 65000;
    private static final int DEFAULT_UNK_ID = 1;

    // ---- ONNX / tokenizer 状态 ----
    private final OrtEnvironment env;
    private final OrtSession encoderSession;
    private final OrtSession decoderSession;
    private final Model srcSpModel;
    private final SentencePieceAlgorithm spAlgorithm;

    private final Map<String, Integer> tokenToId = new HashMap<>();
    private String[] idToToken = new String[0];

    private int padId = DEFAULT_PAD_ID;
    private int eosId = DEFAULT_EOS_ID;
    private int decoderStartId = DEFAULT_DECODER_START_ID;
    private int unkId = DEFAULT_UNK_ID;

    private OnnxZhEnTranslator(Context context) throws IOException, OrtException {
        File modelDir = new File(context.getFilesDir(), ASSET_DIR);
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            throw new IOException("Failed to create model dir: " + modelDir);
        }

        File encoderFile = new File(modelDir, ENCODER_NAME);
        File decoderFile = new File(modelDir, DECODER_NAME);
        File srcSpmFile = new File(modelDir, SRC_SPM_NAME);
        File vocabFile = new File(modelDir, VOCAB_NAME);
        File configFile = new File(modelDir, CONFIG_NAME);

        AssetManager am = context.getAssets();
        copyAssetIfNeeded(am, ASSET_DIR + "/" + ENCODER_NAME, encoderFile);
        copyAssetIfNeeded(am, ASSET_DIR + "/" + DECODER_NAME, decoderFile);
        copyAssetIfNeeded(am, ASSET_DIR + "/" + SRC_SPM_NAME, srcSpmFile);
        copyAssetIfNeeded(am, ASSET_DIR + "/" + VOCAB_NAME, vocabFile);
        copyAssetIfNeeded(am, ASSET_DIR + "/" + CONFIG_NAME, configFile);

        readIdsFromConfig(configFile.toPath());
        readVocab(vocabFile.toPath());

        env = OrtEnvironment.getEnvironment();
        encoderSession = env.createSession(encoderFile.getAbsolutePath(), new OrtSession.SessionOptions());
        decoderSession = env.createSession(decoderFile.getAbsolutePath(), new OrtSession.SessionOptions());

        srcSpModel = Model.parseFrom(srcSpmFile.toPath());
        spAlgorithm = new SentencePieceAlgorithm(false, Scoring.HIGHEST_SCORE);
    }

    private static void copyAssetIfNeeded(AssetManager am, String assetName, File outFile) throws IOException {
        if (outFile.exists()) return;
        try (InputStream is = am.open(assetName);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) >= 0) {
                fos.write(buf, 0, len);
            }
        }
    }

    // ---- 对外翻译 API ----
    public String translate(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return trimmed;
        if (!containsCjk(trimmed)) return trimmed;

        try {
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
            String out = decodeTgt(decodedIds);
            return (out == null || out.trim().isEmpty()) ? trimmed : out;
        } catch (Exception e) {
            Log.e(TAG, "translate failed", e);
            return trimmed;
        }
    }

    // ---- 源侧编码：SentencePiece + vocab ----
    private int[] encodeSrc(String text) {
        List<Integer> spIds = srcSpModel.encodeNormalized(text, spAlgorithm);
        if (spIds == null || spIds.isEmpty()) {
            return new int[0];
        }

        List<String> pieces = new ArrayList<>();
        for (int id : spIds) {
            String p = safeIdToPiece(srcSpModel, id);
            if (p != null) pieces.add(p);
        }
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

    // ---- 目标侧解码：vocab id -> token -> 模拟 DecodePieces ----
    private String decodeTgt(List<Integer> allIds) {
        if (allIds == null || allIds.isEmpty()) {
            return "";
        }

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
            if ("<pad>".equals(tok) || "</s>".equals(tok) || "<unk>".equals(tok)) {
                continue;
            }
            tokens.add(tok);
        }

        return decodePiecesLikeSentencePiece(tokens);
    }

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

    // ---- 贪心解码 ----
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
            int nextId = argmax(lastLogits);
            if (nextId == eosId || nextId == padId) {
                break;
            }
            generated.add(nextId);
        }
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

    // ---- config / vocab 解析 ----
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
     * 关键：对 JSON 中的 \\uXXXX 做反转义，还原出真实字符（如 ▁）。
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

    // ---- 小工具 ----
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

    @Override
    public void close() {
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
