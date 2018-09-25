package soft.model.enm;



/**
 * 排序规则
 *
 * @author FanPei
 *
 */
public enum SortRule {

	Desc((byte) 0), Asc((byte) 1);

	private byte nCode;

	private SortRule(byte _nCode) {
		this.nCode = _nCode;
	}

	public byte getValue() {
		return nCode;
	}

	public static SortRule getValues(byte code) throws Exception {
		switch (code) {

		case 0:
			return Desc;
		case 1:
			return Asc;

		default:
			throw new Exception("[SortRule]命令参数值转换错误");
		}
	}
}
