package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*

internal fun readMerchantOffers(nbtTag: NbtTag, mappings: NbtPropertyReadMappings): MerchantOffers {
    val compound = nbtTag.compound()
    val offers = compound.requiredTag<NbtList>("Recipes").value.mapTo(mutableListOf()) { tag ->
        val offer = tag.compound()
        MerchantOffer(
            readItemCost(offer.requiredTag("buy"), mappings),
            offer.optionalTag<NbtCompound>("buyB")?.let { readItemCost(it, mappings) },
            readItemStack(offer.requiredTag("sell"), mappings),
            offer.int("uses", 0),
            offer.int("maxUses", 4),
            offer.boolean("rewardExp", true),
            offer.int("specialPrice", 0),
            offer.int("demand", 0),
            offer.optionalTag<NbtFloat>("priceMultiplier")?.value ?: 0f,
            offer.int("xp", 1),
            mappings.readProperties(offer, NbtPropertyScope("merchant_offer"), OFFER_FIELDS)
        )
    }
    return MerchantOffers(
        offers,
        mappings.readProperties(compound, NbtPropertyScope("merchant_offers"), setOf("Recipes"))
    )
}

internal fun writeMerchantOffers(value: MerchantOffers, mappings: NbtPropertyWriteMappings): NbtCompound {
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope("merchant_offers"), setOf("Recipes"))
    fields["Recipes"] = NbtList(value.offers.map { offer ->
        val offerFields = mappings.writeProperties(offer.properties, NbtPropertyScope("merchant_offer"), OFFER_FIELDS)
        offerFields["buy"] = writeItemCost(offer.baseCostA, mappings)
        offer.costB?.let { offerFields["buyB"] = writeItemCost(it, mappings) }
        offerFields["sell"] = writeItemStack(offer.result, mappings)
        offerFields["uses"] = NbtInt(offer.uses)
        offerFields["maxUses"] = NbtInt(offer.maxUses)
        offerFields["rewardExp"] = NbtByte(if (offer.rewardExp) 1 else 0)
        offerFields["specialPrice"] = NbtInt(offer.specialPriceDiff)
        offerFields["demand"] = NbtInt(offer.demand)
        offerFields["priceMultiplier"] = NbtFloat(offer.priceMultiplier)
        offerFields["xp"] = NbtInt(offer.xp)
        NbtCompound(offerFields)
    })
    return NbtCompound(fields)
}

private fun readItemCost(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): ItemCost {
    val count = nbtCompound.int("count", 1)
    require(count > 0) { "An ItemCost count must be positive" }
    return ItemCost(
        ItemId.parse(nbtCompound.string("id")), count,
        decodeComponents(nbtCompound.optionalTag<NbtCompound>("components") ?: NbtCompound(emptyMap()), mappings),
        mappings.readProperties(nbtCompound, NbtPropertyScope("item_cost"), COST_FIELDS)
    )
}

private fun writeItemCost(value: ItemCost, mappings: NbtPropertyWriteMappings): NbtCompound {
    require(value.count > 0) { "An ItemCost count must be positive" }
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope("item_cost"), COST_FIELDS)
    fields["id"] = NbtString(value.itemId.toString())
    fields["count"] = NbtInt(value.count)
    if (value.components.entries.isNotEmpty()) fields["components"] = encodeComponents(value.components, mappings)
    return NbtCompound(fields)
}

private val OFFER_FIELDS =
    setOf("buy", "buyB", "sell", "uses", "maxUses", "rewardExp", "specialPrice", "demand", "priceMultiplier", "xp")
private val COST_FIELDS = setOf("id", "count", "components")
