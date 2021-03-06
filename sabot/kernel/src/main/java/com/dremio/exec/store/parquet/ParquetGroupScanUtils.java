/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.parquet;

import static com.dremio.exec.planner.acceleration.IncrementalUpdateUtils.UPDATE_COLUMN;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import com.carrotsearch.hppc.cursors.ObjectLongCursor;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.store.StoragePluginConfig;
import com.dremio.common.types.TypeProtos.MajorType;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.types.Types;
import com.dremio.exec.physical.EndpointAffinity;
import com.dremio.exec.physical.base.GroupScan;
import com.dremio.exec.physical.base.ScanStats;
import com.dremio.exec.physical.base.ScanStats.GroupScanProperty;
import com.dremio.exec.physical.base.SubScan;
import com.dremio.exec.planner.cost.ScanCostFactor;
import com.dremio.exec.planner.physical.visitor.GlobalDictionaryFieldInfo;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.dfs.DefaultPathFilter;
import com.dremio.exec.store.dfs.FileSelection;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.dfs.FileSystemWrapper;
import com.dremio.exec.store.dfs.ReadEntryFromHDFS;
import com.dremio.exec.store.dfs.ReadEntryWithPath;
import com.dremio.exec.store.dfs.easy.FileWork;
import com.dremio.exec.store.parquet.Metadata.ColumnMetadata;
import com.dremio.exec.store.parquet.Metadata.ParquetFileMetadata;
import com.dremio.exec.store.parquet.Metadata.ParquetTableMetadataBase;
import com.dremio.exec.store.parquet.Metadata.RowGroupMetadata;
import com.dremio.exec.store.schedule.AffinityCreator;
import com.dremio.exec.store.schedule.CompleteWork;
import com.dremio.exec.store.schedule.EndpointByteMap;
import com.dremio.exec.store.schedule.EndpointByteMapImpl;
import com.dremio.exec.util.ImpersonationUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class ParquetGroupScanUtils {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ParquetGroupScanUtils.class);

  private final FileSystemPlugin plugin;  // Just the config (not the actual plugin)
  private final ParquetFormatPlugin formatPlugin;
  private String selectionRoot;
  private List<SchemaPath> columns;
  private List<RowGroupInfo> rowGroupInfos;
  private List<FilterCondition> conditions;

  private final List<ReadEntryWithPath> entries;  // rowGroupInfos should be sufficient for equals() and hashCode()
  private Map<String,FileStatus> fileStatusMap;
  private final FileSystemWrapper fs;
  private boolean usedMetadataCache = false;
  private List<EndpointAffinity> endpointAffinities;
  private ListMultimap<Integer, RowGroupInfo> mappings;
  private final Map<String, GlobalDictionaryFieldInfo> globalDictionaryColumns;

  /**
   * The parquet table metadata may have already been read
   * from a metadata cache file earlier; we can re-use during
   * the ParquetGroupScanUtils and avoid extra loading time.
   */
  private Metadata.ParquetTableMetadataBase parquetTableMetadata = null;
  /*
   * total number of non-null value for each column in each parquet file.
   */
  private Map<SchemaPath, Long> columnValueCounts;
  // Map from file names to maps of column name to partition value mappings
  private Map<String, Map<SchemaPath, Object>> partitionValueMap = Maps.newHashMap();
  private Set<String> fileSet;
  private Map<SchemaPath, MajorType> columnTypeMap = Maps.newHashMap();

  private final String userName;
  private final List<String> tableSchemaPath;
  private final BatchSchema schema;
  private final boolean includeModTime;

  /**
   * total number of rows (obtained from parquet footer)
   */
  private long rowCount;

  public ParquetGroupScanUtils(
      String userName,
      FileSelection selection,
      FileSystemPlugin plugin,
      ParquetFormatPlugin formatPlugin,
      String selectionRoot,
      List<SchemaPath> columns,
      List<String> tableSchemaPath,
      BatchSchema schema,
      boolean includeModeTime,
      Map<String, GlobalDictionaryFieldInfo> globalDictionaryColumns,
      List<FilterCondition> conditions)
      throws IOException {
    this.userName = userName;
    this.schema = schema;
    this.tableSchemaPath = tableSchemaPath;
    this.includeModTime = includeModeTime;
    this.formatPlugin = formatPlugin;
    this.conditions = conditions;
    this.columns = columns;
    this.fs = ImpersonationUtil.createFileSystem(userName, plugin.getFsConf());
    this.plugin = plugin;
    this.selectionRoot = selectionRoot;

    final FileSelection fileSelection = expandIfNecessary(selection);

    this.entries = Lists.newArrayList();
    final List<FileStatus> files = fileSelection.getStatuses(fs);
    for (FileStatus file : files) {
      entries.add(new ReadEntryWithPath(file.getPath().toString()));
    }
    fileStatusMap = FluentIterable.from(files).uniqueIndex(new Function<FileStatus, String>() {
      @Nullable
      @Override
      public String apply(@Nullable FileStatus input) {
        return Path.getPathWithoutSchemeAndAuthority(input.getPath()).toString();
      }
    });

    this.globalDictionaryColumns = (globalDictionaryColumns == null)? Collections.<String, GlobalDictionaryFieldInfo>emptyMap() : globalDictionaryColumns;
    init();
  }

  /**
   * expands the selection's folders if metadata cache is found for the selection root.<br>
   * If the selection has already been expanded or no metadata cache was found, does nothing
   *
   * @param selection actual selection before expansion
   * @return new selection after expansion, if no expansion was done returns the input selection
   *
   * @throws IOException
   */
  private FileSelection expandIfNecessary(FileSelection selection) throws IOException {
    if (selection.isExpanded()) {
      return selection;
    }
    if (!fs.isDirectory(new Path(selection.getSelectionRoot()))) {
      return selection;
    }
    Path metaFilePath = new Path(selection.getSelectionRoot(), Metadata.METADATA_FILENAME);
    if (!fs.exists(metaFilePath)) { // no metadata cache
      return selection;
    }

    return initFromMetadataCache(selection, metaFilePath);
  }

  public List<ReadEntryWithPath> getEntries() {
    return entries;
  }

  public ParquetFormatConfig getFormatConfig() {
    return this.formatPlugin.getConfig();
  }

  public StoragePluginConfig getEngineConfig() {
    return this.formatPlugin.getStorageConfig();
  }

  public FileSystemPlugin getPlugin() {
    return plugin;
  }

  public String getSelectionRoot() {
    return selectionRoot;
  }

  public Set<String> getFileSet() {
    return fileSet;
  }

  public Map<String, FileStatus> getFileStatusMap() {
    return fileStatusMap;
  }

  public Map<String, Map<SchemaPath, Object>> getPartitionValueMap() {
    return partitionValueMap;
  }

  public Map<SchemaPath, MajorType> getColumnTypeMap() {
    return columnTypeMap;
  }

  public boolean hasFiles() {
    return true;
  }

  public Collection<String> getFiles() {
    return fileSet;
  }

  public Configuration getFsConf() {
    return plugin.getFsConf();
  }

  public Map<String, GlobalDictionaryFieldInfo> getGlobalDictionaryColumns() {
    return globalDictionaryColumns;
  }

  /**
   * When reading the very first footer, any column is a potential partition column. So for the first footer, we check
   * every column to see if it is single valued, and if so, add it to the list of potential partition columns. For the
   * remaining footers, we will not find any new partition columns, but we may discover that what was previously a
   * potential partition column now no longer qualifies, so it needs to be removed from the list.
   * @return whether column is a potential partition column
   */
  private boolean checkForPartitionColumn(ColumnMetadata columnMetadata, boolean first, long rowCount) {
    SchemaPath schemaPath = SchemaPath.getCompoundPath(columnMetadata.getName());
    if (schemaPath.getAsUnescapedPath().equals(UPDATE_COLUMN)) {
      return true;
    }
    final PrimitiveTypeName primitiveType;
    final OriginalType originalType;
    if (this.parquetTableMetadata.hasColumnMetadata()) {
      primitiveType = this.parquetTableMetadata.getPrimitiveType(columnMetadata.getName());
      originalType = this.parquetTableMetadata.getOriginalType(columnMetadata.getName());
    } else {
      primitiveType = columnMetadata.getPrimitiveType();
      originalType = columnMetadata.getOriginalType();
    }
    if (first) {
      if (hasSingleValue(columnMetadata, rowCount)) {
        columnTypeMap.put(schemaPath, getType(primitiveType, originalType));
        return true;
      } else {
        return false;
      }
    } else {
      if (!columnTypeMap.keySet().contains(schemaPath)) {
        return false;
      } else {
        if (!hasSingleValue(columnMetadata, rowCount)) {
          columnTypeMap.remove(schemaPath);
          return false;
        }
        if (!getType(primitiveType, originalType).equals(columnTypeMap.get(schemaPath))) {
          columnTypeMap.remove(schemaPath);
          return false;
        }
      }
    }
    return true;
  }

  private MajorType getType(PrimitiveTypeName type, OriginalType originalType) {
    if (originalType != null) {
      switch (originalType) {
        case DECIMAL:
          return Types.optional(MinorType.DECIMAL18);
        case DATE:
          return Types.optional(MinorType.DATE);
        case TIME_MILLIS:
          return Types.optional(MinorType.TIME);
        case TIMESTAMP_MILLIS:
          return Types.optional(MinorType.TIMESTAMP);
        case UTF8:
          return Types.optional(MinorType.VARCHAR);
        case UINT_8:
          return Types.optional(MinorType.UINT1);
        case UINT_16:
          return Types.optional(MinorType.UINT2);
        case UINT_32:
          return Types.optional(MinorType.UINT4);
        case UINT_64:
          return Types.optional(MinorType.UINT8);
        case INT_8:
          return Types.optional(MinorType.TINYINT);
        case INT_16:
          return Types.optional(MinorType.SMALLINT);
      }
    }

    switch (type) {
      case BOOLEAN:
        return Types.optional(MinorType.BIT);
      case INT32:
        return Types.optional(MinorType.INT);
      case INT64:
        return Types.optional(MinorType.BIGINT);
      case FLOAT:
        return Types.optional(MinorType.FLOAT4);
      case DOUBLE:
        return Types.optional(MinorType.FLOAT8);
      case BINARY:
      case FIXED_LEN_BYTE_ARRAY:
        return Types.optional(MinorType.VARBINARY);
      case INT96:
        return Types.optional(MinorType.TIMESTAMP);
      default:
        // Should never hit this
        throw new UnsupportedOperationException("Unsupported type:" + type);
    }
  }

  private boolean hasSingleValue(ColumnMetadata columnChunkMetaData, long rowCount) {
    // Return try if min == max and there are no null values or all of them are null values.
    return (columnChunkMetaData != null) &&
      ((columnChunkMetaData.hasSingleValue() && (columnChunkMetaData.getNulls() == null || columnChunkMetaData.getNulls() == 0) ||
        (columnChunkMetaData.getNulls() != null && rowCount == columnChunkMetaData.getNulls())));
  }

  public void modifyFileSelection(FileSelection selection) {
    entries.clear();
    fileSet = Sets.newHashSet();
    for (String fileName : selection.getFiles()) {
      entries.add(new ReadEntryWithPath(fileName));
      fileSet.add(fileName);
    }

    List<RowGroupInfo> newRowGroupList = Lists.newArrayList();
    for (RowGroupInfo rowGroupInfo : rowGroupInfos) {
      if (fileSet.contains(rowGroupInfo.getPath())) {
        newRowGroupList.add(rowGroupInfo);
      }
    }
    this.rowGroupInfos = newRowGroupList;
  }

  public MajorType getTypeForColumn(SchemaPath schemaPath) {
    return columnTypeMap.get(schemaPath);
  }

  public static class RowGroupInfo extends ReadEntryFromHDFS implements CompleteWork, FileWork {

    private EndpointByteMap byteMap;
    private int rowGroupIndex;
    private long rowCount;  // rowCount = -1 indicates to include all rows.
    private List<EndpointAffinity> affinities;
    private Map<SchemaPath, Long> columnValueCounts;

    public RowGroupInfo(String path, long start, long length, int rowGroupIndex, long rowCount, Map<SchemaPath, Long> columnValueCounts) {
      super(path, start, length);
      this.rowGroupIndex = rowGroupIndex;
      this.rowCount = rowCount;
      this.columnValueCounts = columnValueCounts == null? Collections.<SchemaPath, Long>emptyMap() : columnValueCounts;
    }

    public RowGroupReadEntry getRowGroupReadEntry() {
      return new RowGroupReadEntry(this.getPath(), this.getStart(), this.getLength(), this.rowGroupIndex);
    }

    public int getRowGroupIndex() {
      return this.rowGroupIndex;
    }

    public int compareTo(CompleteWork o) {
      return Long.compare(getTotalBytes(), o.getTotalBytes());
    }

    @Override
    public List<EndpointAffinity> getAffinity() {
      return affinities;
    }

    public long getTotalBytes() {
      return this.getLength();
    }

    public EndpointByteMap getByteMap() {
      return byteMap;
    }

    public Map<SchemaPath, Long> getColumnValueCounts() {
      return columnValueCounts;
    }

    public void setEndpointByteMap(EndpointByteMap byteMap) {
      this.byteMap = byteMap;
      this.affinities = Lists.newArrayList();
      final Iterator<ObjectLongCursor<CoordinationProtos.NodeEndpoint>> nodeEndpointIterator = byteMap.iterator();
      while (nodeEndpointIterator.hasNext()) {
        ObjectLongCursor<NodeEndpoint> nodeEndPoint = nodeEndpointIterator.next();
        affinities.add(new EndpointAffinity(nodeEndPoint.key, nodeEndPoint.value));
      }
    }

    public long getRowCount() {
      return rowCount;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      RowGroupInfo that = (RowGroupInfo) o;
      return rowGroupIndex == that.rowGroupIndex &&
          rowCount == that.rowCount;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(super.hashCode(), rowGroupIndex, rowCount);
    }
  }


  /**
   * Create and return a new file selection based on reading the metadata cache file.
   *
   * This function also initializes a few of ParquetGroupScanUtils's fields as appropriate.
   *
   * @param selection initial file selection
   * @param metaFilePath metadata cache file path
   * @return file selection read from cache
   *
   * @throws IOException
   * @throws UserException when the updated selection is empty, this happens if the user selects an empty folder.
   */
  private FileSelection
  initFromMetadataCache(FileSelection selection, Path metaFilePath) throws IOException {
    // get the metadata for the root directory by reading the metadata file
    // parquetTableMetadata contains the metadata for all files in the selection root folder, but we need to make sure
    // we only select the files that are part of selection (by setting fileSet appropriately)

    // get (and set internal field) the metadata for the directory by reading the metadata file
    this.parquetTableMetadata = Metadata.readBlockMeta(fs, metaFilePath.toString(), formatPlugin.getConfig(), plugin.getFsConf());
    if (formatPlugin.getConfig().autoCorrectCorruptDates) {
      ParquetReaderUtility.correctDatesInMetadataCache(this.parquetTableMetadata);
    }
    List<String> fileNames = Lists.newArrayList();
    List<FileStatus> fileStatuses = selection.getStatuses(fs);

    final Path first = fileStatuses.get(0).getPath();
    if (fileStatuses.size() == 1 && selection.getSelectionRoot().equals(first.toString())) {
      // we are selecting all files from selection root. Expand the file list from the cache
      for (Metadata.ParquetFileMetadata file : parquetTableMetadata.getFiles()) {
        fileNames.add(file.getPath());
      }
      // we don't need to populate fileSet as all files are selected
    } else {
      // we need to expand the files from fileStatuses
      for (FileStatus status : fileStatuses) {
        if (status.isDirectory()) {
          //TODO [DRILL-4496] read the metadata cache files in parallel
          final Path metaPath = new Path(status.getPath(), Metadata.METADATA_FILENAME);
          final Metadata.ParquetTableMetadataBase metadata = Metadata.readBlockMeta(fs, metaPath.toString(), formatPlugin.getConfig(), plugin.getFsConf());
          for (Metadata.ParquetFileMetadata file : metadata.getFiles()) {
            fileNames.add(file.getPath());
          }
        } else {
          final Path path = Path.getPathWithoutSchemeAndAuthority(status.getPath());
          fileNames.add(path.toString());
        }
      }

      // populate fileSet so we only keep the selected row groups
      fileSet = Sets.newHashSet(fileNames);
    }

    if (fileNames.isEmpty()) {
      // no files were found, most likely we tried to query some empty sub folders
      throw UserException.validationError().message("The table you tried to query is empty").build(logger);
    }

    // when creating the file selection, set the selection root in the form /a/b instead of
    // file:/a/b.  The reason is that the file names above have been created in the form
    // /a/b/c.parquet and the format of the selection root must match that of the file names
    // otherwise downstream operations such as partition pruning can break.
    final Path metaRootPath = Path.getPathWithoutSchemeAndAuthority(new Path(selection.getSelectionRoot()));
    this.selectionRoot = metaRootPath.toString();

    // Use the FileSelection constructor directly here instead of the FileSelection.create() method
    // because create() changes the root to include the scheme and authority; In future, if create()
    // is the preferred way to instantiate a file selection, we may need to do something different...
    // WARNING: file statuses and file names are inconsistent
    FileSelection newSelection = new FileSelection(selection.getStatuses(fs), fileNames, metaRootPath.toString());

    newSelection.setExpanded();
    return newSelection;
  }

  private void init() throws IOException {
    final Stopwatch watch = Stopwatch.createStarted();
    columnTypeMap.put(SchemaPath.getSimplePath(UPDATE_COLUMN), Types.optional(MinorType.BIGINT));
    if (entries.size() == 1) {
      Path p = Path.getPathWithoutSchemeAndAuthority(new Path(entries.get(0).getPath()));
      Path metaPath = null;
      if (fs.isDirectory(p)) {
        // Using the metadata file makes sense when querying a directory; otherwise
        // if querying a single file we can look up the metadata directly from the file
        metaPath = new Path(p, Metadata.METADATA_FILENAME);
      }
      if (metaPath != null && fs.exists(metaPath)) {
        usedMetadataCache = true;
        if (parquetTableMetadata == null) {
          parquetTableMetadata = Metadata.readBlockMeta(fs, metaPath.toString(), formatPlugin.getConfig(), plugin.getFsConf());
          if (formatPlugin.getConfig().autoCorrectCorruptDates) {
            ParquetReaderUtility.correctDatesInMetadataCache(this.parquetTableMetadata);
          }
        }
      } else {
        parquetTableMetadata = Metadata.getParquetTableMetadata(plugin.getFooterCache(), fs, p.toString(), formatPlugin.getConfig(), plugin.getFsConf());
      }
    } else {
      Path p = Path.getPathWithoutSchemeAndAuthority(new Path(selectionRoot));
      Path metaPath = new Path(p, Metadata.METADATA_FILENAME);
      if (fs.isDirectory(new Path(selectionRoot)) && fs.exists(metaPath)) {
        usedMetadataCache = true;
        if (parquetTableMetadata == null) {
          parquetTableMetadata = Metadata.readBlockMeta(fs, metaPath.toString(), formatPlugin.getConfig(), plugin.getFsConf());
          if (formatPlugin.getConfig().autoCorrectCorruptDates) {
            ParquetReaderUtility.correctDatesInMetadataCache(this.parquetTableMetadata);
          }
        }
        if (fileSet != null) {
          parquetTableMetadata = removeUnneededRowGroups(parquetTableMetadata);
        }
      } else {
        final List<FileStatus> fileStatuses = Lists.newArrayList();
        for (ReadEntryWithPath entry : entries) {
          getFiles(entry.getPath(), fileStatuses);
        }
        parquetTableMetadata = Metadata.getParquetTableMetadata(plugin.getFooterCache(), fs, fileStatuses, formatPlugin.getConfig(), plugin.getFsConf());
      }
    }

    if (fileSet == null) {
      fileSet = Sets.newHashSet();
      for (ParquetFileMetadata file : parquetTableMetadata.getFiles()) {
        fileSet.add(file.getPath());
      }
    }

    ListMultimap<String, NodeEndpoint> hostEndpointMap = FluentIterable.from(plugin.getExecutors())
      .index(new Function<NodeEndpoint, String>() {
        @Override
        public String apply(NodeEndpoint endpoint) {
          return endpoint.getAddress();
        }
      });

    rowGroupInfos = Lists.newArrayList();
    for (ParquetFileMetadata file : parquetTableMetadata.getFiles()) {
      int rgIndex = 0;
      for (RowGroupMetadata rg : file.getRowGroups()) {
        // non null value counts for column
        long rowCount = rg.getRowCount();
        Map<SchemaPath, Long> rowGroupColumnValueCounts = Maps.newHashMap();
        for (ColumnMetadata column : rg.getColumns()) {
          SchemaPath schemaPath = SchemaPath.getCompoundPath(column.getName());
          if (column.getNulls() != null) {
            rowGroupColumnValueCounts.put(schemaPath, rowCount - column.getNulls());
          }
        }
        RowGroupInfo rowGroupInfo =
          new RowGroupInfo(file.getPath(), rg.getStart(), rg.getLength(), rgIndex, rg.getRowCount(), rowGroupColumnValueCounts);

        EndpointByteMap endpointByteMap = new EndpointByteMapImpl();
        for (String host : rg.getHostAffinity().keySet()) {
          if (hostEndpointMap.containsKey(host)) {
            endpointByteMap
                .add(getRandom(hostEndpointMap.get(host)), (long) (rg.getHostAffinity().get(host) * rg.getLength()));
          }
        }
        rowGroupInfo.setEndpointByteMap(endpointByteMap);
        rgIndex++;
        rowGroupInfos.add(rowGroupInfo);
      }
    }

    this.endpointAffinities = AffinityCreator.getAffinityMap(rowGroupInfos);

    columnValueCounts = Maps.newHashMap();
    this.rowCount = 0;
    boolean first = true;
    for (ParquetFileMetadata file : parquetTableMetadata.getFiles()) {
      for (RowGroupMetadata rowGroup : file.getRowGroups()) {
        long rowCount = rowGroup.getRowCount();
        for (ColumnMetadata column : rowGroup.getColumns()) {
          SchemaPath schemaPath = SchemaPath.getCompoundPath(column.getName());
          Long previousCount = columnValueCounts.get(schemaPath);
          if (previousCount != null) {
            if (previousCount != GroupScan.NO_COLUMN_STATS) {
              if (column.getNulls() != null) {
                Long newCount = rowCount - column.getNulls();
                columnValueCounts.put(schemaPath, columnValueCounts.get(schemaPath) + newCount);
              }
            }
          } else {
            if (column.getNulls() != null) {
              Long newCount = rowCount - column.getNulls();
              columnValueCounts.put(schemaPath, newCount);
            } else {
              columnValueCounts.put(schemaPath, GroupScan.NO_COLUMN_STATS);
            }
          }
          boolean partitionColumn = checkForPartitionColumn(column, first, rowCount);
          if (partitionColumn) {
            Map<SchemaPath, Object> map = partitionValueMap.get(file.getPath());
            if (map == null) {
              map = Maps.newHashMap();
              partitionValueMap.put(file.getPath(), map);
            }
            Object value = map.get(schemaPath);
            Object currentValue = column.getMaxValue();
            if (value != null) {
              if (value != currentValue) {
                columnTypeMap.remove(schemaPath);
              }
            } else {
              map.put(schemaPath, currentValue);
            }
          } else {
            columnTypeMap.remove(schemaPath);
          }
        }
        this.rowCount += rowGroup.getRowCount();
        first = false;
      }
      partitionValueMap.get(file.getPath());
      Map<SchemaPath, Object> map = partitionValueMap.get(file.getPath());
      if (map == null) {
        map = Maps.newHashMap();
        partitionValueMap.put(file.getPath(), map);
      }
      map.put(SchemaPath.getSimplePath(UPDATE_COLUMN), fileStatusMap.get(file.getPath()).getModificationTime());

    }
    logger.debug("Took {} ms to gather Parquet table metadata.", watch.elapsed(TimeUnit.MILLISECONDS));
  }

  private <T> T getRandom(List<T> list) {
    if (list == null || list.size() == 0) {
      return null;
    }
    return list.get(ThreadLocalRandom.current().nextInt(0, list.size()));
  }

  private ParquetTableMetadataBase removeUnneededRowGroups(ParquetTableMetadataBase parquetTableMetadata) {
    List<ParquetFileMetadata> newFileMetadataList = Lists.newArrayList();
    for (ParquetFileMetadata file : parquetTableMetadata.getFiles()) {
      if (fileSet.contains(file.getPath())) {
        newFileMetadataList.add(file);
      }
    }

    ParquetTableMetadataBase metadata = parquetTableMetadata.clone();
    metadata.assignFiles(newFileMetadataList);
    return metadata;
  }

  public Iterator getSplits() {
    throw new UnsupportedOperationException("getSplits is not supported in deprecated ParquetGroupScanUtils");
  }

  public SubScan getSpecificScan(List work) throws ExecutionSetupException {
    throw new UnsupportedOperationException("getSpecificScan is not supported in deprecated ParquetGroupScanUtils");
  }

  /**
   * Calculates the affinity each endpoint has for this scan, by adding up the affinity each endpoint has for each
   * rowGroup
   *
   * @return a list of EndpointAffinity objects
   */
  public List<EndpointAffinity> getOperatorAffinity() {
    return this.endpointAffinities;
  }

  private void getFiles(String path, List<FileStatus> fileStatuses) throws IOException {
    Path p = Path.getPathWithoutSchemeAndAuthority(new Path(path));
    FileStatus fileStatus = fs.getFileStatus(p);
    if (fileStatus.isDirectory()) {
      for (FileStatus f : fs.listStatus(p, new DefaultPathFilter())) {
        getFiles(f.getPath().toString(), fileStatuses);
      }
    } else {
      fileStatuses.add(fileStatus);
    }
  }


  private List<RowGroupReadEntry> convertToReadEntries(List<RowGroupInfo> rowGroups) {
    List<RowGroupReadEntry> entries = Lists.newArrayList();
    for (RowGroupInfo rgi : rowGroups) {
      RowGroupReadEntry entry = new RowGroupReadEntry(rgi.getPath(), rgi.getStart(), rgi.getLength(), rgi.getRowGroupIndex());
      entries.add(entry);
    }
    return entries;
  }

  public int getMaxParallelizationWidth() {
    return rowGroupInfos.size();
  }

  public List<SchemaPath> getColumns() {
    return columns;
  }

  public Map<SchemaPath, Long> getColumnValueCounts() {
    return columnValueCounts;
  }

  public long getRowCount() {
    return rowCount;
  }

  public ScanStats getScanStats() {
    int columnCount = columns == null ? 20 : columns.size();
    if (GroupScan.ALL_COLUMNS.equals(columns)) {
      columnCount = schema.getFieldCount();
    }
    int sortFactor = getSortFactor();
    if(hasConditions()){
      long estRowCount = (long) (this.rowCount * 0.15d);
      return new ScanStats(GroupScanProperty.NO_EXACT_ROW_COUNT, estRowCount, 1 / sortFactor, estRowCount * columnCount / sortFactor);
    } else {
      return new ScanStats(GroupScanProperty.EXACT_ROW_COUNT, rowCount, 1 / sortFactor, rowCount * columnCount / sortFactor);
    }
  }

  @JsonIgnore
  public ScanCostFactor getScanCostFactor() {
    return ScanCostFactor.of(ScanCostFactor.PARQUET.getFactor() / getSortFactor());
  }



  public String getDigest() {
    return toString();
  }

  public String toString() {
    return "ParquetGroupScanUtils [entries=" + entries
        + ", selectionRoot=" + selectionRoot
        + ", numFiles=" + getEntries().size()
        + ", usedMetadataFile=" + usedMetadataCache
        + ", conditions=" + conditions
        + ", columns=" + columns + "]";
  }

  @JsonIgnore
  public List<RowGroupInfo> getRowGroupInfos() {
    return rowGroupInfos;
  }

  public boolean hasConditions(){
    return conditions != null && !conditions.isEmpty();
  }

  public int getSortFactor() {
    if (!hasConditions()) {
      return 1;
    }
    FilterCondition condition = Iterables.getFirst(conditions, null);
    int sortIndex = condition.getSort();
    if (sortIndex < 0) {
      return 1;
    }
    // somewhat arbitrary formula, but the goal is for the factor to be lower if the sort column is secondary than if it's primary
    // e.g. table is sorted on a, b, then a filter on a should be cheaper than a filter on b
    return Math.max(1, 16 / (sortIndex + 1));
  }

  public List<FilterCondition> getConditions() {
    return conditions;
  }


  /**
   *  Return column value count for the specified column. If does not contain such column, return 0.
   */
  public long getColumnValueCount(SchemaPath column) {
    return columnValueCounts.containsKey(column) ? columnValueCounts.get(column) : 0;
  }

  public List<SchemaPath> getPartitionColumns() {
    List<SchemaPath> list = Lists.newArrayList();
    return ImmutableList.<SchemaPath>builder().addAll(list).addAll(columnTypeMap.keySet()).build();
  }

}
