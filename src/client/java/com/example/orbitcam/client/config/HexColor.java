package com.example.orbitcam.client.config;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * 颜色的 JSON 适配器：把 ARGB 整数以 {@code #RRGGBBAA} 形式的字符串存取，
 * 同时兼容旧配置里直接写成整数（十进制）的情况。
 */
public final class HexColor extends TypeAdapter<Integer> {

	/** 不透明白色，作为解析失败时的兜底颜色 */
	public static final int WHITE = 0xFFFFFFFF;

	@Override
	public void write(JsonWriter out, Integer value) throws IOException {
		out.value("#" + String.format("%08X", value == null ? WHITE : value));
	}

	@Override
	public Integer read(JsonReader in) throws IOException {
		if (in.peek() == JsonToken.NULL) {
			in.nextNull();
			return WHITE;
		}
		// 兼容旧格式：直接写了十进制整数
		if (in.peek() == JsonToken.NUMBER) {
			return in.nextInt();
		}
		String text = in.nextString().trim();
		try {
			return (int) Long.parseLong(text.startsWith("#") ? text.substring(1) : text, 16);
		} catch (NumberFormatException e) {
			return WHITE;
		}
	}
}
