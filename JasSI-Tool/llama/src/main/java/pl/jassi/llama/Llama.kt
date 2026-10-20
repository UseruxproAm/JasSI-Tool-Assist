package pl.jassi.llama

object Llama {
    init { System.loadLibrary("jassi_llama") }
    external fun load(path: String): Boolean
    external fun generate(prompt: String, maxTokens: Int, threads: Int): String
    external fun release()
}
