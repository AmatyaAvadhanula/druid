SELECT LEAD(col1) OVER ( PARTITION BY col3 ORDER BY col1 ) LEAD_col1 FROM "fewRowsAllData.parquet"