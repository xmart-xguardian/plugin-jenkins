package io.jenkins.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.InvisibleAction;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.HttpClients;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


public class Xguardian extends Notifier implements SimpleBuildStep, Serializable {
    private String email;
    private String password;
    private String applicationId;
    private String scanVersion;
    private boolean sastCheckbox;
    private boolean scaCheckbox;
    private static final long serialVersionUID = 1L;
    public Xguardian () {

    }

    @DataBoundConstructor
    public Xguardian(String email, String password) {
        this.email = email;
        this.password = password;
    }

    @SuppressWarnings("unused")
    public String getEmail() {
        return email;
    }
    @SuppressWarnings("unused")
    public String getSenha() {
        return password;
    }

    @SuppressWarnings("unused")
    public String getApplicationId() {
        return applicationId;
    }

    @SuppressWarnings("unused")
    public String getScanVersion() {
        return scanVersion;
    }

    @SuppressWarnings("unused")
    public boolean isSastCheckbox() {
        return sastCheckbox;
    }

    @SuppressWarnings("unused")
    public boolean isScaCheckbox() {
        return scaCheckbox;
    }

    @DataBoundSetter
    @SuppressWarnings("unused")
    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    @DataBoundSetter
    @SuppressWarnings("unused")
    public void setScanVersion(String scanVersion) {
        this.scanVersion = scanVersion;
    }
    @DataBoundSetter
    @SuppressWarnings("unused")
    public void setSastCheckbox(boolean sastCheckbox) {
        this.sastCheckbox = sastCheckbox;
    }

    @DataBoundSetter
    @SuppressWarnings("unused")
    public void setScaCheckbox(boolean scaCheckbox) {
        this.scaCheckbox = scaCheckbox;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        // Salvar os dados como variáveis de ambiente
        build.addAction(new EmailSenhaAction(email, password));

        // Testar a conexão
        FormValidation validation = doTestConnection(email, password);
        listener.getLogger().println("Connection test status: " + validation.getMessage());

        if (validation.kind.equals(FormValidation.Kind.OK)) {
            // Chamar rota de login para obter o token
            String jsonResponseLogin = realizarRequisicaoPost(email, password, listener);

            if (jsonResponseLogin != null) {
                ObjectMapper objectMapperLogin = new ObjectMapper();
                try {
                    JsonNode jsonNodeLogin = objectMapperLogin.readTree(jsonResponseLogin);

                    if (jsonNodeLogin.has("token")) {
                        String token = jsonNodeLogin.get("token").asText();

                        // Salvar o token como variável de ambiente se estiver presente

                        EnvVars envVarsToken = new EnvVars();
                        envVarsToken.put("TOKEN", token);

                        //for (Map.Entry<String, String> entry : envVarsToken.entrySet()) {
                            //listener.getLogger().println("Variável de ambiente: " + entry.getKey() + " = " + entry.getValue());
                        //}

                        // Chamar rota específica para obter IDs de aplicações
                        String appIdsResponse = getAppIds(token, listener);

                        // Processar a resposta e obter os IDs das aplicações
                        if (appIdsResponse != null) {
                            ObjectMapper appIdsObjectMapper = new ObjectMapper();
                            try {
                                JsonNode appIdsJson = appIdsObjectMapper.readTree(appIdsResponse);

                                // Certifique-se de que a resposta é um array
                                if (appIdsJson.isArray()) {
                                    // Iterar sobre os IDs das aplicações
                                    for (JsonNode appId : appIdsJson) {
                                        // Obter o ID de cada aplicação
                                        int applicationId = appId.get("id").asInt();

                                        if (applicationId == Integer.parseInt(this.applicationId)) {
                                            //listener.getLogger().println("ID da Aplicação Selecionada: " + applicationId);

                                            // Exemplo: Salvar o ID como variável de ambiente se estiver presente
                                            EnvVars envVarsAppId = new EnvVars();
                                            envVarsAppId.put("SELECTED_APPLICATION_ID", Integer.toString(applicationId));

                                            //for (Map.Entry<String, String> entry : envVarsAppId.entrySet()) {
                                            //    listener.getLogger().println("Variável de ambiente: " + entry.getKey() + " = " + entry.getValue());
                                            //}

                                            // Salvar o ID da aplicação como variável de ambiente usando InvisibleAction
                                            build.addAction(new ApplicationIdAction(Integer.toString(applicationId)));
                                        }
                                    }
                                } else {
                                    listener.getLogger().println("The answer is not an array of IDs.");
                                }
                            } catch (IOException e) {
                                listener.getLogger().println("Error processing JSON response: " + e.getMessage());
                            }
                        } else {
                            listener.getLogger().println("Unable to get specific route IDs.");
                        }
                        if (uploadData(token, applicationId, scanVersion, sastCheckbox, scaCheckbox, build, listener)) {
                            listener.getLogger().println("Data uploaded successfully.");
                        } else {
                            listener.getLogger().println("Failed to upload data.");
                        }

                    } else {
                        listener.getLogger().println("Login route JSON response does not contain 'token' field.");
                        return false; // Encerre a execução se não houver token na rota de login
                    }
                } catch (IOException e) {
                    listener.getLogger().println("Error processing login route JSON response: " + e.getMessage());
                    return false; // Encerre a execução em caso de erro
                }
            } else {
                listener.getLogger().println("Login route failed. It will not be possible to obtain the token.");
                return false; // Encerre a execução em caso de falha na rota de login
            }
        } else {
            listener.getLogger().println("The connection was not successful. The POST request will not be made.");
        }
        return true;
    }

    private FormValidation doTestConnection(String email, String password) {
        try {
            URL url = new URL("https://auth.development.xguardianplatform.io/login");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("email", email);
            requestBody.put("password", password);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            connection.disconnect();

            return FormValidation.ok("Connection successful!");
        } catch (IOException e) {
            return FormValidation.error("Connection fail. Check your credentials.");
        }
    }

    private String realizarRequisicaoPost(String email, String password, BuildListener listener) {
        try {

            URL url = new URL("https://auth.development.xguardianplatform.io/login");

            // Abrir a conexão
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Configurar a conexão para um POST
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            // Montar os dados a serem enviados no corpo da requisição
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("email", email);
            requestBody.put("password", password);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }


            // Ler a resposta
            StringBuilder response = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
            }

            // Fechar a conexão
            connection.disconnect();


            return response.toString();
        } catch (Exception e) {
            listener.getLogger().println("Error when performing the POST request: " + e.getMessage());
            return null;
        }
    }

    private String getAppIds(String token, BuildListener listener) {

        try {
            // Montar a URL da rota específica com o token como parâmetro
            URL url = new URL("https://uploader-mvp.development.xguardianplatform.io/get_apps_total?token=" + token);

            // Abrir a conexão
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Configurar a conexão para um GET
            connection.setRequestMethod("GET");

            // Ler a resposta
            StringBuilder response = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
            }

            // Fechar a conexão
            connection.disconnect();

            // Retornar a resposta
            return response.toString();
        } catch (Exception e) {
            listener.getLogger().println("Erro ao obter os IDs: " + e.getMessage());
            return null;
        }
    }

    private boolean uploadData(String token, String applicationId, String scanVersion, boolean sastCheckbox, boolean scaCheckbox, AbstractBuild<?, ?> build, BuildListener listener) {
        try {
            // Obter o caminho do workspace
            FilePath workspace = build.getWorkspace();

            // Verificar se o workspace é nulo
            if (workspace == null) {
                listener.getLogger().println("Workspace not found.");
                return false;
            }

            // Criar um arquivo temporário para armazenar o arquivo zip
            File zipFile = File.createTempFile("workspace", ".zip");
            zipWorkspace(workspace, zipFile, listener);

            // Construir a URL da rota de upload
            String uploadUrl = "https://uploader-mvp.development.xguardianplatform.io/upload-url";
            URL uploadUrlX = new URL(uploadUrl);
            HttpURLConnection connection = (HttpURLConnection) uploadUrlX.openConnection();

            // Configurar método HTTP para POST
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            // Configurar cabeçalhos da requisição
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            // Construir o corpo da requisição em formato JSON
            JSONObject requestBody = new JSONObject();
            requestBody.put("token", token);
            requestBody.put("app_id", Integer.parseInt(applicationId));
            requestBody.put("scan_version", scanVersion);
            requestBody.put("file_type", "application/zip");
            requestBody.put("sca", Boolean.toString(scaCheckbox));
            requestBody.put("sast", Boolean.toString(sastCheckbox));

            // Enviar o corpo da requisição
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Obter a resposta da requisição
            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Ler a resposta da requisição
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String inputLine;
                    StringBuilder response = new StringBuilder();

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }

                    String url = extractUrlFromResponse(response.toString(), listener);

                    if (url != null) {
                        // Chamar função para realizar a solicitação adicional
                        performAdditionalRequest(url, zipFile, listener);
                        return true; // Indicar que o upload foi bem-sucedido
                    } else {
                        listener.getLogger().println("URL extraída da resposta é nula. O upload falhou.");
                        return false; // Indicar que o upload falhou
                    }
                }
            } else {
                // Lidar com erros de resposta
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String errorLine;

                    while ((errorLine = errorReader.readLine()) != null) {
                        errorResponse.append(errorLine);
                    }

                    listener.getLogger().println("Error sending data to the UploadData upload route. Response code: " + responseCode);
                    listener.getLogger().println("Error response: " + errorResponse);

                    return false; // Indicar que o upload falhou
                }
            }
                } catch (IOException e) {
                    // Lidar com erros de leitura da resposta de erro
                    listener.getLogger().println("Error reading upload route error response: " + e.getMessage());

                    return false; // Indicar que o upload falhou
                } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void zipWorkspace(FilePath workspace, File zipFile, BuildListener listener) throws IOException, InterruptedException {
        try (OutputStream os = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            // Obter todos os arquivos no workspace
            FilePath[] files = workspace.list("**/*");

            // Adicionar cada arquivo ao arquivo zip
            for (FilePath file : files) {
                String entryName = file.getRemote().substring(workspace.getRemote().length() + 1);
                zos.putNextEntry(new ZipEntry(entryName));

                try (InputStream is = file.read()) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                }
                zos.closeEntry();
            }
        }
        listener.getLogger().println("Workspace zipped successfully.");
    }

    private String extractUrlFromResponse(String response, BuildListener listener) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonResponse = objectMapper.readTree(response);

            if (jsonResponse.has("url")) {
                return jsonResponse.get("url").asText();
            } else {
                listener.getLogger().println("Resposta JSON da rota de upload não contém o campo 'url'.");
                return null;
            }
        } catch (Exception e) {
            listener.getLogger().println("Erro na extração da URL da resposta da rota de upload: " + e.getMessage());
            return null;
        }
    }


    private void performAdditionalRequest(String url, File zipFile, BuildListener listener) {
        try {
            HttpClient client = HttpClients.createDefault();

            // Construção do corpo da requisição
            FileEntity fileEntity = new FileEntity(zipFile, ContentType.create("application/zip"));

            // Construção da requisição
            HttpPut httpPut = new HttpPut(url);
            httpPut.setHeader("Content-Type", "application/zip");
            httpPut.setEntity(fileEntity);

            // Execução da requisição
            HttpResponse response = client.execute(httpPut);

            // Verificação da resposta
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200 || statusCode == 201) {
                // Leitura da resposta, se necessário
                listener.getLogger().println("Upload successful");
            } else {
                // Leitura da mensagem de erro da resposta, se houver
                String errorResponse = null;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    errorResponse = sb.toString();
                } catch (IOException e) {
                    listener.getLogger().println("Error reading response error message: " + e.getMessage());
                }

                // Exibição do código de resposta e da mensagem de erro completa
                listener.getLogger().println("Error sending data to the Perform upload route. Response code: " + statusCode);
                if (errorResponse != null && !errorResponse.isEmpty()) {
                    listener.getLogger().println("Error message: " + errorResponse);
                }
            }

        } catch (IOException e) {
            // Lidar com exceções de IO, se ocorrerem
            listener.getLogger().println("Error when making additional request: " + e.getMessage());
        }
    }


    public static class EmailSenhaAction extends InvisibleAction {
        private final String email;
        private final String password;

        public EmailSenhaAction(String email, String password) {
            this.email = email;
            this.password = password;
        }

        @SuppressWarnings("unused")
        public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
            env.put("EMAIL", email);
            env.put("SENHA", password);
        }
    }

    @SuppressWarnings("unused")
    public static class TokenAction extends InvisibleAction {
        private final String token;

        public TokenAction(String token) {
            this.token = token;
        }

        public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
            env.put("TOKEN", token);
        }
    }

    public static class ApplicationIdAction extends InvisibleAction {
        private final String applicationId;

        public ApplicationIdAction(String applicationId) {
            this.applicationId = applicationId;
        }

        @SuppressWarnings("unused")
        public String getApplicationId() {
            return applicationId;
        }

        @SuppressWarnings("unused")
        public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
            if (applicationId != null && !applicationId.isEmpty()) {
                env.put("SELECTED_APPLICATION_ID", applicationId);
            }
        }
    }


    @Extension
    public static class XguardianDescriptor extends BuildStepDescriptor<Publisher> {

        public XguardianDescriptor() {
            super(Xguardian.class);
        }

        @NotNull
        @Override
        public String getDisplayName() {
            return "XGuardian Plugin";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }
    }
}
