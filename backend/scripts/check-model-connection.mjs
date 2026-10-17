// 匿名连接诊断：不读取密钥、不发送学习资料、不调用生成接口。
import { createEvaluationProvider } from './evaluation-provider.mjs';

const provider = createEvaluationProvider({ baseUrl: 'https://api.deepseek.com', maxCalls: 1 });
const result = await provider.preflight();
console.log(JSON.stringify(result, null, 2));
if (!result.passed) process.exitCode = 1;
