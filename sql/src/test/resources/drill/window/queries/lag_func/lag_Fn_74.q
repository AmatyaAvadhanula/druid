SELECT col0 , LEAD ( col0 ) OVER wdw lead_col0, LAG( col0 ) OVER wdw lag_col0 FROM "fewRowsAllData.parquet" where col0 >= 5000 and col0 <= 10000 WINDOW wdw AS ( PARTITION BY col2 ORDER BY col0 )