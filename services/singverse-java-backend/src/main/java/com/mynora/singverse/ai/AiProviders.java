package com.mynora.singverse.ai;

import java.util.*;
import static com.mynora.singverse.ai.AiDtos.*;

public final class AiProviders {
    private AiProviders() {}
    private static Provider model(String id,String name,String protocol,String endpoint,String mode) { return new Provider(id,name,"MODEL",protocol,endpoint,mode); }
    private static Provider search(String id,String name,String endpoint) { return new Provider(id,name,"SEARCH",id.toUpperCase(Locale.ROOT),endpoint,""); }
    public static final List<Provider> ALL=List.of(
        model("openai","OpenAI","RESPONSES","https://api.openai.com/v1/responses","JSON_SCHEMA"),
        model("anthropic","Anthropic","ANTHROPIC","https://api.anthropic.com/v1/messages","JSON_SCHEMA"),
        model("gemini","Google Gemini","GEMINI","https://generativelanguage.googleapis.com/v1beta","JSON_SCHEMA"),
        model("deepseek","DeepSeek","CHAT_COMPLETIONS","https://api.deepseek.com/chat/completions","JSON_OBJECT"),
        model("qwen","通义千问 / 百炼","CHAT_COMPLETIONS","https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions","JSON_OBJECT"),
        model("doubao","豆包 / 火山方舟","CHAT_COMPLETIONS","https://ark.cn-beijing.volces.com/api/v3/chat/completions","JSON_OBJECT"),
        model("zhipu","智谱","CHAT_COMPLETIONS","https://open.bigmodel.cn/api/paas/v4/chat/completions","JSON_OBJECT"),
        model("moonshot","月之暗面","CHAT_COMPLETIONS","https://api.moonshot.cn/v1/chat/completions","JSON_OBJECT"),
        model("siliconflow","硅基流动","CHAT_COMPLETIONS","https://api.siliconflow.cn/v1/chat/completions","JSON_OBJECT"),
        model("openrouter","OpenRouter","CHAT_COMPLETIONS","https://openrouter.ai/api/v1/chat/completions","JSON_SCHEMA"),
        model("ollama","Ollama","CHAT_COMPLETIONS","http://127.0.0.1:11434/v1/chat/completions","JSON_SCHEMA"),
        model("lmstudio","LM Studio","CHAT_COMPLETIONS","http://127.0.0.1:1234/v1/chat/completions","JSON_SCHEMA"),
        model("custom","通用兼容模型","CHAT_COMPLETIONS","","JSON_SCHEMA"),
        search("tavily","Tavily","https://api.tavily.com/search"),
        search("exa","Exa","https://api.exa.ai/search"),
        search("brave","Brave Search","https://api.search.brave.com/res/v1/web/search"),
        search("bocha","博查","https://api.bocha.cn/v1/web-search"),
        search("searxng","SearXNG",""), search("custom-search","通用搜索接口","")
    );
}
