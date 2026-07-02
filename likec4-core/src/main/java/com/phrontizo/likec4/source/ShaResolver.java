package com.phrontizo.likec4.source;

@FunctionalInterface
public interface ShaResolver {
  String resolveSha(String project, String ref) throws Exception;
}
