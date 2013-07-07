package io.reign;

/**
 * 
 * @author ypai
 * 
 */
public interface PathScheme {

    public String getBasePath();

    public String getAbsolutePath(PathType pathType);

    public String getAbsolutePath(PathType pathType, String relativePath);

    public String getAbsolutePath(PathType pathType, String... pathTokens);

    public String joinPaths(String... paths);

    public String[] tokenizePath(String path);

    public String joinTokens(String... pathTokens);

    public boolean isValidPathToken(String pathToken);

    public boolean isValidPath(String path);

    public String getParentPath(String path);

    /**
     * Try to parse input String as canonical ID with some embedded information
     * 
     * @param canonicalId
     * @return Map of values; or null
     * @throws IllegalArgumentException
     *             if there is a parsing error
     */
    public CanonicalId parseCanonicalId(String canonicalId);

    /**
     * 
     * @param canonicalId
     * @return valid path representation of CanonicalId
     */
    public String toPathToken(CanonicalId canonicalId);
}
