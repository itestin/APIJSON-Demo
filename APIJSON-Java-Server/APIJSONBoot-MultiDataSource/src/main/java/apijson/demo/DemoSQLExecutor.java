/*Copyright ©2016 TommyLemon(https://github.com/TommyLemon/APIJSON)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/

package apijson.demo;

import apijson.JSON;
import apijson.RequestMethod;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson.JSONObject;
import com.vesoft.nebula.jdbc.impl.NebulaDriver;
import com.zaxxer.hikari.HikariDataSource;

import java.io.Serializable;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import apijson.Log;
import apijson.boot.DemoApplication;
import apijson.framework.APIJSONSQLExecutor;
import apijson.orm.SQLConfig;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static apijson.framework.APIJSONConstant.PRIVACY_;
import static apijson.framework.APIJSONConstant.USER_;


/**
 * SQL 执行器，支持连接池及多数据源
 * 具体见 https://github.com/Tencent/APIJSON/issues/151
 *
 * @author Lemon
 */
public class DemoSQLExecutor extends APIJSONSQLExecutor {
    public static final String TAG = "DemoSQLExecutor";

    // Redis 缓存 <<<<<<<<<<<<<<<<<<<<<<<
    public static final RedisTemplate<String, String> REDIS_TEMPLATE;
    static {
        REDIS_TEMPLATE = new RedisTemplate<>();
        try {
            REDIS_TEMPLATE.setConnectionFactory(new JedisConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 6379)));
            REDIS_TEMPLATE.setKeySerializer(new StringRedisSerializer());
            REDIS_TEMPLATE.setHashValueSerializer(new GenericToStringSerializer<>(Serializable.class));
            REDIS_TEMPLATE.setValueSerializer(new GenericToStringSerializer<>(Serializable.class));
            //    REDIS_TEMPLATE.setValueSerializer(new FastJsonRedisSerializer<List<JSONObject>>(List.class));
            REDIS_TEMPLATE.afterPropertiesSet();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    //  可重写以下方法，支持 Redis 等单机全局缓存或分布式缓存
    @Override
    public List<JSONObject> getCache(String sql, SQLConfig config) {
        List<JSONObject> list = super.getCache(sql, config);
        if (list == null) {
            try {
                list = JSON.parseArray(REDIS_TEMPLATE.opsForValue().get(sql), JSONObject.class);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return list;
    }

    @Override
    public synchronized void putCache(String sql, List<JSONObject> list, SQLConfig config) {
        super.putCache(sql, list, config);

        String table = config != null && config.isMain() ? config.getTable() : null;
        if (table != null && DemoSQLConfig.CONFIG_TABLE_LIST.contains(table) == false) {
            try {
                if (config.isExplain() || RequestMethod.isHeadMethod(config.getMethod(), true)) {
                    REDIS_TEMPLATE.opsForValue().set(sql, JSON.toJSONString(list), 10 * 60, TimeUnit.SECONDS);
                } else {
                    REDIS_TEMPLATE.opsForValue().set(sql, JSON.toJSONString(list), USER_.equals(table) || PRIVACY_.equals(table) ? 10 * 60 : 60, TimeUnit.SECONDS);
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public synchronized void removeCache(String sql, SQLConfig config) {
        super.removeCache(sql, config);
        try {
            if (config.getMethod() == RequestMethod.DELETE) { // 避免缓存击穿
                REDIS_TEMPLATE.expire(sql, 60, TimeUnit.SECONDS);
            } else {
                REDIS_TEMPLATE.delete(sql);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    // Redis 缓存 >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>


    // 适配连接池，如果这里能拿到连接池的有效 Connection，则 SQLConfig 不需要配置 dbVersion, dbUri, dbAccount, dbPassword
    @Override
    public Connection getConnection(SQLConfig config) throws Exception {
        if ("NEBULA".equals(config.getDatabase())) {  // 3.0.0 及以下要这样连接
            String uri = config.getDBUri();

            int start = uri.indexOf("://");
            String prefix = uri.substring(0, start);

            uri = uri.substring(start + "://".length());
            int end = uri.indexOf("/");
            String space = uri.substring(end + 1);

            Properties props = new Properties();
            props.put("url", prefix + "://" + space);
            props.put("graphSpace", space);

            NebulaDriver driver = new NebulaDriver(uri.substring(0, end));
            return driver.connect(prefix + "://" + space, props);
            //    return DriverManager.getConnection("jdbc:nebula://JDBC_TEST_SPACE", "root", "nebula");
        }

        String datasource = config.getDatasource();
        Log.d(TAG, "getConnection  config.getDatasource() = " + datasource);

        String key = datasource + "-" + config.getDatabase();
        Connection c = connectionMap.get(key);
        if (datasource != null && (c == null || c.isClosed())) {
            try {
                DataSource ds;
                switch (datasource) {
                    case "HIKARICP":
                        ds = DemoApplication.getApplicationContext().getBean(HikariDataSource.class);
                        // 另一种方式是 DemoDataSourceConfig 初始化获取到 DataSource 后给静态变量 DATA_SOURCE_HIKARICP 赋值： ds = DemoDataSourceConfig.DATA_SOURCE_HIKARICP.getConnection();
                        break;
                    default:
                        Map<String, DruidDataSource> dsMap = DemoApplication.getApplicationContext().getBeansOfType(DruidDataSource.class);
                        // 另一种方式是 DemoDataSourceConfig 初始化获取到 DataSource 后给静态变量 DATA_SOURCE_DRUID 赋值： ds = DemoDataSourceConfig.DATA_SOURCE_DRUID.getConnection();
                        switch (datasource) {
                            case "DRUID-TEST":
                                ds = dsMap.get("druidTestDataSource");
                                break;
                            case "DRUID-ONLINE":
                                ds = dsMap.get("druidOnlineDataSource");
                                break;
                            case "DRUID":
                                ds = dsMap.get("druidDataSource");
                                break;
                            default:
                                ds = null;
                                break;
                        }
                        break;
                }

                connectionMap.put(key, ds == null ? null : ds.getConnection());
            } catch (Exception e) {
                Log.e(TAG, "getConnection   try { "
                        + "DataSource ds = DemoApplication.getApplicationContext().getBean(DataSource.class); .."
                        + "} catch (Exception e) = " + e.getMessage());
            }
        }

        // 必须最后执行 super 方法，因为里面还有事务相关处理。
        // 如果这里是 return c，则会导致 增删改 多个对象时只有第一个会 commit，即只有第一个对象成功插入数据库表
        return super.getConnection(config);
    }

    // 取消注释支持 !key 反选字段 和 字段名映射，需要先依赖插件 https://github.com/APIJSON/apijson-column
    //	@Override
    //	protected String getKey(SQLConfig config, ResultSet rs, ResultSetMetaData rsmd, int tablePosition, JSONObject table,
    //			int columnIndex, Map<String, JSONObject> childMap) throws Exception {
    //		return ColumnUtil.compatOutputKey(super.getKey(config, rs, rsmd, tablePosition, table, columnIndex, childMap), config.getTable(), config.getMethod());
    //	}

    // 不需要隐藏字段这个功能时，取消注释来提升性能
    //	@Override
    //	protected boolean isHideColumn(SQLConfig config, ResultSet rs, ResultSetMetaData rsmd, int tablePosition,
    //			JSONObject table, int columnIndex, Map<String, JSONObject> childMap) throws SQLException {
    //		return false;
    //	}

}
