package com.example.photos.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OnnxZhEnTranslatorTest {

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

    @Test
    public void translateChinese_returnsEnglishText() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String input = "今天天气不错，出去散步吧。";

        OnnxZhEnTranslator translator = OnnxZhEnTranslator.getInstance(context);
        assertNotNull("translator init failed (assets or ORT not available)", translator);

        String output = translator.translate(input);
        assertNotNull("translation is null", output);
        assertFalse("translation is empty", output.trim().isEmpty());
        assertNotEquals("translation identical to input; translator likely fell back to original", input, output);
        assertFalse("translation still contains CJK; translator may be failing", containsCjk(output));
    }
}
