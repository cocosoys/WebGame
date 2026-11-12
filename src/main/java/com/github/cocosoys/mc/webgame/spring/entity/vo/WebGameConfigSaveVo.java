package com.github.cocosoys.mc.webgame.spring.entity.vo;

/**
 * 配置文件保存响应 VO（POST /erp/config/save）。
 *
 * <p>字段 {@code tip} 须与前端 configPage mixin 消费的 {@code res.data.tip} 对齐，
 * 否则保存成功后的提示弹窗不出现。</p>
 */
public class WebGameConfigSaveVo {

    private String file;
    private String tip;

    public static WebGameConfigSaveVo of(String file, String tip) {
        WebGameConfigSaveVo vo = new WebGameConfigSaveVo();
        vo.file = file;
        vo.tip = tip;
        return vo;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String getTip() {
        return tip;
    }

    public void setTip(String tip) {
        this.tip = tip;
    }
}
