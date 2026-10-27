package com.dp.agent.discover;

import java.util.List;

/** 单一内容源；实现方各自负责 HTTP 与解析，失败由 DiscoverService 隔离。 */
interface NewsSource {

    String label();

    List<Article> fetch() throws Exception;
}
