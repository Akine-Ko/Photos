package com.example.photos.privacy;

/**
 * 隐私策略三档模型：
 * PUBLIC    正常显示
 * SENSITIVE 模糊/遮罩，点击后可经生物识别短期解锁
 * SECRET    不展示 MediaStore 原图，转存加密到应用私有目录
 */
public enum PrivacyPolicy {
    PUBLIC(0),
    SENSITIVE(1),
    SECRET(2);

    private final int code;
    PrivacyPolicy(int code){ this.code = code; }
    public int getCode(){ return code; }
    public static PrivacyPolicy from(int code){
        for (PrivacyPolicy p: values()) if (p.code == code) return p;
        return PUBLIC;
    }
}