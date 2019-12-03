package com.box.l10n.mojito.rest.entity;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * This is an exact copy of {@link com.box.l10n.mojito.rest.entity.SourceAsset}
 * This should be updated if it either one changes.
 *
 * @author wyau
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceAsset {

    private Long repositoryId;
    private String path;
    private String content;
    private String branch;
    private String branchCreatedByUsername;
    private Long addedAssetId;
    private PollableTask pollableTask;
    private FilterConfigIdOverride filterConfigIdOverride;
    private List<String> filterOptions;

    public Long getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(Long repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getAddedAssetId() {
        return addedAssetId;
    }

    public void setAddedAssetId(Long addedAssetId) {
        this.addedAssetId = addedAssetId;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getBranchCreatedByUsername() {
        return branchCreatedByUsername;
    }

    public void setBranchCreatedByUsername(String branchCreatedByUsername) {
        this.branchCreatedByUsername = branchCreatedByUsername;
    }

    public FilterConfigIdOverride getFilterConfigIdOverride() {
        return filterConfigIdOverride;
    }

    public void setFilterConfigIdOverride(FilterConfigIdOverride filterConfigIdOverride) {
        this.filterConfigIdOverride = filterConfigIdOverride;
    }

    public List<String> getFilterOptions() {
        return filterOptions;
    }

    public void setFilterOptions(List<String> filterOptions) {
        this.filterOptions = filterOptions;
    }

    @JsonProperty
    public PollableTask getPollableTask() {
        return pollableTask;
    }

    /**
     * @JsonIgnore because this pollableTask is read only data generated by the
     * server side, it is not aimed to by external process via WS
     *
     * @param pollableTask
     */
    @JsonIgnore
    public void setPollableTask(PollableTask pollableTask) {
        this.pollableTask = pollableTask;
    }
}
