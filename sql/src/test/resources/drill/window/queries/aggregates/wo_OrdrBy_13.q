SELECT MIN(col_int) OVER (PARTITION BY col_tmstmp) min_int, col_tmstmp, col_int FROM "smlTbl.parquet" WHERE col_vchar_52 = "HXXXXXXXXXXXXXXXXXXXXXXXXXIXXXXXXXXXXXXXXXXXXXXXXXXJ"