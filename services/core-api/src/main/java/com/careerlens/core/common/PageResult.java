package com.careerlens.core.common;

import java.util.List;

public record PageResult<T>(List<T> items,long total,String nextCursor,boolean hasMore) {}
