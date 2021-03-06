package com.bdindex.core;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractButton;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import org.apache.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import com.bdindex.exception.IndexNeedBuyException;
import com.bdindex.exception.IndexNotInServiceException;
import com.bdindex.model.Model;
import com.bdindex.ui.MyTableModel;
import com.bdindex.ui.UIUpdateModel;
import com.bdindex.ui.Util;
import com.selenium.BDIndexAction;
import com.selenium.BDIndexJSExecutor;
import com.selenium.BDIndexUtil;
import com.selenium.Constant;
import com.selenium.ScreenShot;
import com.selenium.Wait;

public class BDIndexCoreWorker extends SwingWorker<Void, UIUpdateModel> {
	private static SimpleDateFormat logDateFormat = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ss");
	private static SimpleDateFormat imgNameDateFormat = new SimpleDateFormat(
			"yyyy-MM-dd");
	private static Logger logger = Logger.getLogger(BDIndexCoreWorker.class);
	private DriverService service;
	private WebDriver webdriver;
	private MyTableModel tableModel;
	private ArrayList<AbstractButton> buttons;
	private JTextArea textArea;

	@SuppressWarnings("unused")
	private BDIndexCoreWorker() {
	}

	public BDIndexCoreWorker(MyTableModel myTableModel,
			ArrayList<AbstractButton> buttons, JTextArea textArea) {
		tableModel = myTableModel;
		this.buttons = buttons;
		this.textArea = textArea;
	}

	private void init() throws Exception {
		service = new ChromeDriverService.Builder()
				.usingDriverExecutable(BDIndexUtil.getDriverFileFromJar())
				.usingAnyFreePort().build();
		service.start();
		webdriver = new RemoteWebDriver(service.getUrl(),
				new ChromeOptions());
		// 第一次发送请求
		webdriver.get(Constant.url);
		// 处理错误页面
		BDIndexUtil.handleErrorPageBeforeLogin(webdriver, 3);
		// 激活浏览器窗口,将浏览器窗口置顶,并不是真正要截图
		((TakesScreenshot) webdriver).getScreenshotAs(OutputType.BYTES);
		// 最大化窗口
		BDIndexAction.maximizeBrowser(webdriver);
		// 处理错误页面
		BDIndexUtil.handleErrorPageBeforeLogin(webdriver, 3);
		// 登录
		BDIndexAction.login(webdriver, service, 3);
		BDIndexUtil.handleErrorPage(webdriver);
	}

	/**
	 * 输入关键词进行搜索
	 * 
	 * @param keyword
	 * @param startDate
	 * @param endDate
	 * @throws Exception
	 */
	private void submitKeyword(String keyword)
			throws Exception {
		// 处理错误页
		BDIndexUtil.handleErrorPage(webdriver);
		// 输入关键字搜索
		BDIndexAction.searchKeyword(webdriver, keyword);
		// 处理关键词需购买的情况
		BDIndexUtil.checkBuyIndexPage(webdriver, service, keyword);
		// 处理关键词不提供服务的情况
		BDIndexUtil.indexNotInServiceCheck(webdriver, service, keyword);
	}

	/**
	 * 精确抓取百度指数
	 */
	private void accurateBDIndex(Model model, String cityID)
			throws Exception {
		//分割时间
		ArrayList<Date[]> list = Util.getDatePairsBetweenDates(model.getStartDate(),
				model.getEndDate());
		String outputDir = BDIndexUtil.getOutputDir(model);
		//输入关键词
		submitKeyword(model.getKeyword());
		Wait.waitForLoad(webdriver);
		//设置地区
		String url = webdriver.getCurrentUrl();
		//当前该逻辑用不到，因为每次输入关键词时，url都会将area信息清掉
		if (url.contains("&area=")) {
			url = url.replaceAll("&area=\\d+", "");//删除已有的area
		}
		webdriver.get(url+"&area="+cityID);//添加新的
		Wait.waitForLoad(webdriver);
		
		url = webdriver.getCurrentUrl();
		if (url.contains("time=")) {
			return;
		}
		String res = (String)((JavascriptExecutor) webdriver).executeScript("return PPval.ppt;");
		String res2 = (String)((JavascriptExecutor) webdriver).executeScript("return PPval.res2;");
		for (int i = 0; i < list.size(); i++) {
			//此处为快速抓取百度指数代码
			//list.get(i)[0]--startDate
			//list.get(i)[1]--endDate
			Date subStartDate = list.get(i)[0];
			Date subEndDate = list.get(i)[1];
			String []wiseIndices = BDIndexJSExecutor.requestWiseIndex(webdriver,model.getKeyword(),res, res2, subStartDate, subEndDate);
			//每次des和image都是不同的，要对应起来
			Calendar tmpCalendar = Calendar.getInstance();
			for (int j = 0; j < wiseIndices.length; j++) {
				String desc = BDIndexJSExecutor.requestImageDes(webdriver,res, res2, wiseIndices[j]);
				String html =  "\"<table style='background-color: #444;'><tbody><tr><td class='view-value'>";
				html += desc.replaceAll("\"", "'");
				html += "</td></tr></tbody></table>\"";
				
				//将渲染后的百度指数div添加到百度指数页面
				int retryCount = 0;
				By indexBy = By.xpath("/html/body/div/table/tbody/tr/td");
				while (retryCount < 5) {
					((JavascriptExecutor)webdriver).executeScript( 
							"var body = document.getElementsByTagName('body');" + 
							"var newDiv = document.createElement('div');" + 
							"newDiv.setAttribute('name', 'songgeb');"+
							"newDiv.innerHTML = " + html  +";" +
							"body[0].appendChild(newDiv);");
					//多拉取来一次，增大图片拉取概率
					try {
						Wait.waitForElementVisible(webdriver, indexBy, 10);
						webdriver.findElement(indexBy);
						WebElement tmp = webdriver.findElement(By.xpath("/html/body/div/table/tbody/tr/td/span[1]/div"));
						String imgURLStr = BDIndexUtil.getURLStringFromStyleText(tmp
								.getCssValue("background"));
						int imgRetryCount = 0;
						while (imgRetryCount < 5) {
							if (BDIndexJSExecutor.requestIndexImg(webdriver, imgURLStr)) {
								break;
							}
							imgRetryCount++;
						}
						break;
					} catch(Exception e) {
						retryCount ++;
						((JavascriptExecutor)webdriver).executeScript(""
								+ "var e = document.getElementsByName('songgeb');\n" + 
								"e[0].parentNode.removeChild(e[0]);");
					}
				}
				
				//截图
				WebElement targetEle = webdriver.findElement(indexBy);
				tmpCalendar.clear();
				tmpCalendar.setTime(subStartDate);
				tmpCalendar.add(Calendar.DAY_OF_MONTH, j);
				String imgFileName = imgNameDateFormat.format(tmpCalendar.getTime());
				ScreenShot.capturePicForAccurateMode(
						(TakesScreenshot) webdriver, targetEle,
						imgFileName + ".png", outputDir);
				//删除添加的百度指数div
				((JavascriptExecutor)webdriver).executeScript(""
						+ "var e = document.getElementsByName('songgeb');\n" + 
						"e[0].parentNode.removeChild(e[0]);");
			}
		}
		OCRUtil.doOCR(outputDir, outputDir);
	}

	private void start() {
		// 防御式编程
		ArrayList<Model> models = tableModel.getValues();
		if (models.size() < 1) {
			logger.warn("关键词数据源0条");
			return;
		}
		// 开始
		publish(new UIUpdateModel(getTextAreaContent(null,
				Constant.Status.Spider_Start), false));
		// 初始化
		try {
			init();
		} catch (Exception e) {
			publish(new UIUpdateModel(getTextAreaContent(null,
					Constant.Status.Spider_InitException), true));
			logger.error(Constant.Status.Spider_InitException, e);
			BDIndexUtil.closeSession(webdriver, service);
			return;
		}
		//获取城市信息
		Wait.waitForLoad(webdriver);
		@SuppressWarnings("unchecked")
		Map<String, String> cities = (Map<String, String>)((JavascriptExecutor)webdriver).executeScript(
				"var strArr = BID['cityIDname'].strArr;\n" + 
				"var sbCities = {};\n" + 
				"for (var i = 0; i < strArr.length; i++) {\n" + 
				"    var str = strArr[i];\n" + 
				"    var items = str.split(',');     \n" + 
				"	 for (var j = 0; j < items.length; j+=2) {\n" + 
				"		sbCities[items[j+1]] = items[j];\n" + 
				"	 }\n" + 
				"}\n" + 
				"sbCities['全国']=0;"+
				"return sbCities;");
		// 执行过程
		UIUpdateModel updateModel = null;
		long startTime = 0;
		for (Model model : models) {
			startTime = System.currentTimeMillis();
			model.setStatus(Constant.Status.Model_Start);
			publish(new UIUpdateModel(getTextAreaContent(model.getKeyword(),
					Constant.Status.Model_Start), false));
			try {
				//check city
				String cityID = "0";//0表示全国
				if (model.getCity() == null) {
					if (model.getProvince() != null) {
						cityID = cities.get(model.getProvince());
					}
				} else {
					cityID = cities.get(model.getCity());
				}
				if (cityID == null) {
					model.setStatus(Constant.Status.Model_City_Error);
					updateModel = new UIUpdateModel(getTextAreaContent(model.getKeyword(),
							Constant.Status.Model_City_Error), false);
					continue;
				}
				
				switch (Constant.currentMode) {
				case Estimate:
					break;
				case Accurate:
					accurateBDIndex(model, cityID);
					break;
				default:
					return;
				}
				model.setStatus(Constant.Status.Model_End);
				updateModel = new UIUpdateModel(getTextAreaContent(
						model.getKeyword(), Constant.Status.Model_End), false);
			} catch (IndexNeedBuyException e) {
				model.setStatus(Constant.Status.Model_IndexNeedBuyException);
				updateModel = new UIUpdateModel(getTextAreaContent(
						model.getKeyword(),
						Constant.Status.Model_IndexNeedBuyException), false);
			} catch (IndexNotInServiceException e) {
				model.setStatus(Constant.Status.Model_IndexNotInServiceException);
				updateModel = new UIUpdateModel(getTextAreaContent(
						model.getKeyword(),
						Constant.Status.Model_IndexNotInServiceException),
						false);
			} catch (Exception e) {
				model.setStatus(Constant.Status.Model_Exception);
				updateModel = new UIUpdateModel(getTextAreaContent(
						model.getKeyword(), Constant.Status.Model_Exception),
						false);
				logger.error(model.getKeyword(), e);
				// 删除当前关键词的结果文件,防止不必要麻烦
				BDIndexUtil.deleteIndexFile(model);
			} finally {
				model.setTime((System.currentTimeMillis() - startTime) / 1000);
				publish(updateModel);
				// 数据汇总统计
				BDIndexSummaryUtil.summary(model);
			}
			// 记录爬虫信息
			String spiderInfoFilePath = BDIndexUtil.getOutputDir(model)
					+ Constant.spiderinfoFilename;
			Util.writeSpiderInfoToFile(spiderInfoFilePath, model);
		}
		publish(new UIUpdateModel(getTextAreaContent(null,
				Constant.Status.Spider_End), true));
		BDIndexUtil.closeSession(webdriver, service);
	}

	/**
	 * 日志日期
	 * 
	 * @return
	 */
	private String logDateString() {
		return "【" + logDateFormat.format(new Date()) + "】";
	}

	/**
	 * 用于在textArea显示的内容
	 * 
	 * @param keyword
	 * @param status
	 * @return
	 */
	private String getTextAreaContent(String keyword, String status) {
		if (keyword == null || keyword.equals("")) {
			return status + logDateString() + "\n";
		}
		return "【" + keyword + "】" + status + logDateString() + "\n";
	}

	@Override
	protected Void doInBackground() throws Exception {
		start();
		return null;
	}

	@Override
	protected void process(List<UIUpdateModel> chunks) {
		super.process(chunks);
		for (int i = 0; i < chunks.size(); i++) {
			UIUpdateModel model = chunks.get(i);
			//model must not be null
			textArea.append(model.getTextAreaContent());
			Util.setButtonsStatus(buttons, model.isButtonEnable());
			tableModel.fireTableDataChanged();
		}
	}
}