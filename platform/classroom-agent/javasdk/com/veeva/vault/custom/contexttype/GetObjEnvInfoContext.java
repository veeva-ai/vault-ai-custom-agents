package com.veeva.vault.custom.contexttype;

import com.veeva.vault.sdk.api.ai.AiContextTypeOutputItem;
import com.veeva.vault.sdk.api.ai.AiContextTypeResolveContext;
import com.veeva.vault.sdk.api.ai.AiContextTypeResolveResponse;
import com.veeva.vault.sdk.api.ai.AiContextTypeRuntimeHandler;
import com.veeva.vault.sdk.api.ai.AiContextTypeRuntimeHandlerInfo;
import com.veeva.vault.sdk.api.ai.AiScope;
import com.veeva.vault.sdk.api.core.ServiceLocator;
import com.veeva.vault.sdk.api.core.VaultInformation;
import com.veeva.vault.sdk.api.core.VaultInformationService;
import com.veeva.vault.sdk.api.executeas.ExecuteAs;
import com.veeva.vault.sdk.api.executeas.ExecuteAsUser;
import com.veeva.vault.sdk.api.json.JsonObject;
import com.veeva.vault.sdk.api.json.JsonService;

/**
 * AI Context Type that exposes the running Vault's identity (vault_id, vault_name, vault_dns)
 * as a JSON envelope, so Object-scoped agent actions can build dynamic Vault links without
 * hard-coding a host name.
 */
@ExecuteAs(ExecuteAsUser.REQUEST_OWNER)
@AiContextTypeRuntimeHandlerInfo(scope = AiScope.OBJECT)
public class GetObjEnvInfoContext implements AiContextTypeRuntimeHandler {

    @Override
    public AiContextTypeResolveResponse onResolve(AiContextTypeResolveContext context) {
        VaultInformationService vaultInfoService = ServiceLocator.locate(VaultInformationService.class);
        VaultInformation vaultInfo = vaultInfoService.getLocalVaultInformation();

        JsonService jsonService = ServiceLocator.locate(JsonService.class);
        JsonObject payload = jsonService.newJsonObjectBuilder()
                .setValue("vault_id", vaultInfo.getId())
                .setValue("vault_name", vaultInfo.getName())
                .setValue("vault_dns", vaultInfo.getDns())
                .build();

        AiContextTypeOutputItem outputItem = context.newOutputItemBuilder()
                .withText(payload.asString())
                .build();

        return context.newSuccessResponseBuilder()
                .appendOutputItem(outputItem)
                .build();
    }
}
