package dev.gatehouse.ai;

import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;

/**
 * The built-in AI (R66): a small model run inside Gatekeeper by llama.cpp (through its Java binding, de.kherud:llama),
 * on the processor only. Shipped inside the program as ai-engine.jar and loaded by dev.octoberjourney.host.BuiltinAi,
 * with the binding and the model it fetched once (opt-in), in a class loader of their own; nothing here is touched on a
 * machine that never turned the built-in AI on. The model is the Qwen2.5 Instruct family, which uses the ChatML prompt
 * form written out here.
 */
public final class Local {
	private Local() {}
	private static LlamaModel model;
	private static String loaded;

	/** One answer. The model is loaded the first time and kept until unload(). */
	public static synchronized String answer(String modelPath, int threads, String system, String user, int maxTokens) {
		if (model == null || !modelPath.equals(loaded)) {
			unload();
			try { LlamaModel.setLogger(de.kherud.llama.args.LogFormat.TEXT, (level, text) -> { }); } catch (Throwable quiet) { /* its own console output then */ }
			model = new LlamaModel(new ModelParameters().setModel(modelPath).setCtxSize(8192).setThreads(threads).setGpuLayers(0));
			loaded = modelPath;
		}
		String prompt = "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
		InferenceParameters p = new InferenceParameters(prompt).setTemperature(0.2f).setNPredict(maxTokens).setStopStrings("<|im_end|>");
		return model.complete(p).replace("<|im_end|>", "").strip();
	}

	/** Frees the model's memory (about 1.5 GB). */
	public static synchronized void unload() {
		if (model != null) { try { model.close(); } catch (RuntimeException ignored) { /* gone anyway */ } }
		model = null;
		loaded = null;
	}
}
