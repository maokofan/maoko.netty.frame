package soft.net.conf;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import soft.common.PropertiesUtil;
import soft.common.StringUtil;
import soft.common.conf.ConfException;

/**
 * 客户端配置文件
 * 
 * @author fanpei
 *
 */
public class ConfigClient extends Conf {
	/**
	 * 毫秒 重连间隔
	 */
	public static final int CONNET_RETRY_INTERVAL = 5000;
	/**
	 * 秒，发送数据超时时间
	 */
	public static final int SENDDATA_TIMEOUT = 10;
	/**
	 * 秒 心跳发送间隔
	 */
	public static byte HEARBEAT_INTERVAL_S;
	/**
	 * 毫秒 心跳发送间隔
	 */
	public static int HEARBEAT_INTERVAL = 10 * 1000;

	/**
	 * 检查断线和发送心跳的线程个数
	 */
	public static int CHECKTDPOOLNUMBER = 1;
	/**
	 * 短连接接收数据最多等待时间
	 */
	public static final int SHORTCONECT_REVDATA_WAITTIMEOUT = 60 * 1000;

	@Deprecated
	private static List<IPAddrPackage> hosts;

	@Deprecated
	public static List<IPAddrPackage> getHosts() {
		return hosts;
	}

	@Deprecated
	public static void init() throws ConfException, IOException {
		Map<String, String> values = PropertiesUtil.getAllProperties(ConfReader.confPath);
		if (values == null || values.isEmpty())
			throw new ConfException(StringUtil.getMsgStr("this conf file {} read error", ConfReader.confPath));
		for (Entry<String, String> v : values.entrySet()) {

			switch (v.getKey()) {
			case "clientips":
				hosts = getHosts(v.getKey(), v.getValue());
				break;

			case "cliHeartInterval":
				HEARBEAT_INTERVAL_S = Byte.parseByte(v.getValue());
				HEARBEAT_INTERVAL = HEARBEAT_INTERVAL_S * 1000;
				break;

			default:
				break;

			}
		}
	}
}
