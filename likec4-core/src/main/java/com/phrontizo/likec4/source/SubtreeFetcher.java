package com.phrontizo.likec4.source;

@FunctionalInterface
public interface SubtreeFetcher {
  SourceBundle fetchSubtree(String project, String sha, String path) throws Exception;
}
