package com.google.cloud.bigtable.hbase.replication.adapters;

import static org.apache.hadoop.hbase.HConstants.LATEST_TIMESTAMP;

import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.replication.regionserver.MetricsSource;
import org.apache.hadoop.hbase.wal.WAL;

/**
 * Approximates the incompatible mutations to nearest compatible mutations when possible.
 * Practically, converts DeleteFamiliBeforeTimestamp to DeleteFamily when delete is requested before
 * "now".
 */
public class ApproximatingIncompatibleMutationAdapter extends IncompatibleMutationAdapter {
  // TODO rename
  /**
   * Threshold to consider the deleteFamilyBefore as a DeleteFamily mutation. When DeleteFamily or
   * HBase translates a DeleteFamily or DeleteRow to DeleteFamilyBeforeTimestamp(now). This is then
   * written to WAL. For local clusters, the WALKey.writeTime() is same as "now" from the
   * DeleteFamilyBeforeTimestamp mutation. However, if the mutation was generated from a different
   * cluster, the WALKey.writeTime and timestamp in DeleteFamilyBeforeTimestamp will have diff of
   * ReplicationLag. Users can set this config to Max(ReplicationLag) to make sure that all the
   * deleteRow/DeleteColumnFamily are correctly interpreted. If you only issue DeleteFamily or
   * DeleteRow mutations, you can set this to Integer.MAX_VALUE. This will lead to any
   * DeleteFamilyBeforeTimestamp where (timestamp < walkey.writeTime()) as DeleteFamily.
   */
  public static final String DELETE_FAMILY_WRITE_THRESHOLD_KEY = "google.bigtable.deletefamily.threshold";
  private static final int DEFAULT_DELETE_FAMILY_WRITE_THRESHOLD_IN_MILLIS = 100;

  private final int deleteFamilyWriteTimeThreshold;

  public ApproximatingIncompatibleMutationAdapter(Configuration conf, MetricsSource metricsSource, Table table) {
    super(conf, metricsSource, table);

    deleteFamilyWriteTimeThreshold = conf.getInt(DELETE_FAMILY_WRITE_THRESHOLD_KEY,
        DEFAULT_DELETE_FAMILY_WRITE_THRESHOLD_IN_MILLIS);
  }

  @Override
  protected List<Cell> adaptIncompatibleMutation(WAL.Entry walEntry, int index) {
    long walWriteTime = walEntry.getKey().getWriteTime();
    Cell cell = walEntry.getEdit().getCells().get(index);
    if (CellUtil.isDeleteFamily(cell)) {
      // TODO Check if its epoch is millis or micros
      // deleteFamily is auto translated to DeleteFamilyBeforeTimestamp(NOW). the WAL write happens
      // later. So walkey.writeTime() should be >= NOW.
      if (walWriteTime >= cell.getTimestamp() &&
          cell.getTimestamp() + deleteFamilyWriteTimeThreshold >= walWriteTime) {
        return Arrays.asList(
            new KeyValue(CellUtil.cloneRow(cell), CellUtil.cloneFamily(cell), (byte[]) null,
                LATEST_TIMESTAMP, KeyValue.Type.DeleteFamily));
      }
      // else can't convert the mutation, throw the exception.
    }
    // Can't convert any other type of mutation.
    throw new UnsupportedOperationException("Unsupported deletes: " + cell);
  }
}
